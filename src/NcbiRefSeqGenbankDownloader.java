import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NcbiRefSeqGenbankDownloader {

    private static final String EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";
    private static final String PLACEHOLDER_EMAIL = "youremail@example.org";

    private final String tool;
    private final String email;
    private final String apiKey; // may be null
    private final HttpClient http;

    // Timestamp of the last HTTP request, used to pace ALL E-utilities calls (see throttle()).
    private long lastRequestNanos = 0L;

    public NcbiRefSeqGenbankDownloader(String tool, String email, String apiKey) {
        this.tool = Objects.requireNonNull(tool);
        this.email = Objects.requireNonNull(email);
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    // Reads a simple list file. Supports: comments (#...), blank lines, and separators (, ; whitespace).
    // Tokens are returned with their original case preserved (normalization happens per-mode), so that
    // full nuccore URLs survive intact for accession/URL parsing.
    static List<String> readListFile(Path path) throws Exception {
        Pattern split = Pattern.compile("[,;\\s]+"); // whitespace/tab/comma/semicolon
        LinkedHashSet<String> out = new LinkedHashSet<>();

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }

            for (String tok : split.split(s)) {
                String item = tok.trim();
                if (!item.isEmpty()) {
                    out.add(item);
                }
            }
        }
        return new ArrayList<>(out);
    }

    enum InputMode { AUTO, GENES, ACCESSIONS }
    enum RecordType { NM, NG }

    // An accession resolved from a raw token, plus an optional sub-range parsed from a nuccore URL.
    record AccRef(String accession, Integer from, Integer to) {}

    static final class Config {
        Path inputFile;
        InputMode inputMode = InputMode.AUTO;
        EnumSet<RecordType> types = EnumSet.noneOf(RecordType.class); // empty => use defaults
        String taxId = "9606"; // Homo sapiens by default
        Integer ngFrom = null;
        Integer ngTo = null;
        Path outDir = Paths.get("out");

        String tool = System.getenv().getOrDefault("NCBI_TOOL", "my_java_ncbi_tool");
        String email = System.getenv().getOrDefault("NCBI_EMAIL", PLACEHOLDER_EMAIL);
        String apiKey = System.getenv("NCBI_API_KEY");

        boolean help = false;
    }

    public static void main(String[] args) throws Exception {
        Config cfg;
        try {
            cfg = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (cfg.help) {
            printUsage();
            return;
        }
        if (cfg.inputFile == null) {
            System.err.println("Input file is required. Use --in <file> or -i <file>.");
            printUsage();
            System.exit(2);
            return;
        }

        List<String> items = readListFile(cfg.inputFile);
        if (items.isEmpty()) {
            System.err.println("Input file is empty: " + cfg.inputFile);
            System.exit(1);
            return;
        }

        // NCBI E-utilities policy asks for a real contact email; warn rather than silently send a placeholder.
        if (cfg.email == null || cfg.email.isBlank() || cfg.email.equalsIgnoreCase(PLACEHOLDER_EMAIL)) {
            System.err.println("Warning: no real NCBI contact email set (using \"" + cfg.email + "\").");
            System.err.println("         NCBI policy expects a valid address. Set --email <you@domain> or NCBI_EMAIL.");
        }

        // Determine input mode if AUTO
        InputMode mode = cfg.inputMode;
        if (mode == InputMode.AUTO) {
            mode = looksLikeAccession(items) ? InputMode.ACCESSIONS : InputMode.GENES;
        }

        // Default record types (both NM_ and NG_) when --types is not specified, for either input mode.
        EnumSet<RecordType> types = cfg.types.isEmpty()
                ? EnumSet.of(RecordType.NM, RecordType.NG)
                : cfg.types;

        var dl = new NcbiRefSeqGenbankDownloader(cfg.tool, cfg.email, cfg.apiKey);
        Files.createDirectories(cfg.outDir);

        int failures = (mode == InputMode.ACCESSIONS)
                ? runAccessionMode(dl, items, cfg, types)
                : runGeneMode(dl, items, cfg, types);

        if (failures > 0) {
            System.err.println("Done with " + failures + " failure(s).");
            System.exit(1);
        } else {
            System.out.println("Done.");
        }
    }

    private static int runGeneMode(NcbiRefSeqGenbankDownloader dl, List<String> genes, Config cfg, EnumSet<RecordType> types) throws InterruptedException {
        System.out.println("Input mode: GENES (symbols)");
        System.out.println("TaxID: " + cfg.taxId);
        System.out.println("Download types: " + types);
        if (types.contains(RecordType.NG) && cfg.ngFrom != null && cfg.ngTo != null) {
            System.out.println("NG_ range: " + cfg.ngFrom + ".." + cfg.ngTo + " (1-based, inclusive)");
        }

        int failures = 0;
        for (String rawSymbol : genes) {
            String geneSymbol = rawSymbol.toUpperCase(Locale.ROOT);
            System.out.println("== " + geneSymbol + " ==");
            try {
                Path geneOut = cfg.outDir.resolve(geneSymbol);
                Files.createDirectories(geneOut);

                Optional<String> geneId = dl.findGeneId(geneSymbol, cfg.taxId);
                if (geneId.isEmpty()) {
                    System.out.println("  GeneID not found");
                    continue;
                }
                System.out.println("  GeneID=" + geneId.get());

                if (types.contains(RecordType.NM)) {
                    List<String> rnaAccs = dl.elinkAccessionVersions(geneId.get(), "gene_nuccore_refseqrna");
                    List<String> nmAccs = rnaAccs.stream().map(String::trim).filter(a -> a.startsWith("NM_")).distinct().toList();
                    System.out.println("  NM_=" + nmAccs.size());
                    failures += dl.fetchAll(nmAccs, acc -> geneOut.resolve(acc + ".gb"), acc -> null, acc -> null);
                }

                if (types.contains(RecordType.NG)) {
                    List<String> geneAccs = dl.elinkAccessionVersions(geneId.get(), "gene_nuccore_refseqgene");
                    List<String> ngAccs = geneAccs.stream().map(String::trim).filter(a -> a.startsWith("NG_")).distinct().toList();
                    System.out.println("  NG_=" + ngAccs.size());
                    failures += dl.fetchAll(ngAccs, acc -> geneOut.resolve(acc + ".gb"), acc -> cfg.ngFrom, acc -> cfg.ngTo);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                failures++;
                System.err.println("  FAILED gene " + geneSymbol + ": " + e.getMessage());
            }
        }
        return failures;
    }

    private static int runAccessionMode(NcbiRefSeqGenbankDownloader dl, List<String> rawItems, Config cfg, EnumSet<RecordType> types) throws IOException, InterruptedException {
        System.out.println("Input mode: ACCESSIONS (ACC.V or NCBI URLs)");
        System.out.println("Download types: " + types);
        if (types.contains(RecordType.NG) && cfg.ngFrom != null && cfg.ngTo != null) {
            System.out.println("NG_ default range: " + cfg.ngFrom + ".." + cfg.ngTo + " (1-based, inclusive)");
        }

        Path out = cfg.outDir.resolve("accessions");
        Files.createDirectories(out);

        // Resolve each token to an accession (+ optional per-URL range), filtered by requested types and de-duplicated.
        LinkedHashMap<String, AccRef> refs = new LinkedHashMap<>();
        for (String item : rawItems) {
            AccRef ref = parseAccRef(item);
            if (ref == null) {
                continue;
            }
            String acc = ref.accession();
            boolean isNM = acc.startsWith("NM_");
            boolean isNG = acc.startsWith("NG_");
            if (isNM && !types.contains(RecordType.NM)) continue;
            if (isNG && !types.contains(RecordType.NG)) continue;
            if (!isNM && !isNG) continue; // ignore non-NM/NG accessions in this tool
            refs.putIfAbsent(acc, ref);   // keep the first occurrence (and its range)
        }

        System.out.println("Accessions to download: " + refs.size());

        int failures = 0;
        for (AccRef ref : refs.values()) {
            String acc = ref.accession();
            // Range precedence: per-URL from/to, else the global --ng-from/--ng-to (NG_ only).
            Integer start = ref.from();
            Integer stop = ref.to();
            if ((start == null || stop == null) && acc.startsWith("NG_") && cfg.ngFrom != null && cfg.ngTo != null) {
                start = cfg.ngFrom;
                stop = cfg.ngTo;
            }
            Path outFile = out.resolve(acc + ".gb");
            try {
                dl.efetchGenbank(acc, outFile, start, stop);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                failures++;
                System.err.println("  FAILED " + acc + ": " + e.getMessage());
            }
        }
        return failures;
    }

    // Functional interface that may be needed if a range mapper throws; kept simple with java.util.function below.
    private interface AccMapper<T> { T apply(String acc); }

    // Download a list of accessions, isolating per-item failures. Returns the number of failures.
    private int fetchAll(List<String> accs, AccMapper<Path> outFile, AccMapper<Integer> from, AccMapper<Integer> to) throws InterruptedException {
        int failures = 0;
        for (String acc : accs) {
            try {
                efetchGenbank(acc, outFile.apply(acc), from.apply(acc), to.apply(acc));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                failures++;
                System.err.println("  FAILED " + acc + ": " + e.getMessage());
            }
        }
        return failures;
    }

    private static boolean looksLikeAccession(List<String> items) {
        for (String s : items) {
            AccRef ref = parseAccRef(s);
            if (ref == null) continue;
            if (ref.accession().startsWith("NM_") || ref.accession().startsWith("NG_")) return true;
        }
        return false;
    }

    // Extract accession.version (and any from/to sub-range) from a raw token or an NCBI nuccore/eutils URL.
    // Examples:
    //   NG_008847.2
    //   https://www.ncbi.nlm.nih.gov/nuccore/NG_008847.2?from=13732&to=58896&report=genbank
    //   https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id=NG_008847.2&seq_start=1&seq_stop=100
    // Returns null if nothing looks like an accession. Matching is case-insensitive.
    static AccRef parseAccRef(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);

        String accession = null;

        // nuccore URL: take the path segment after /nuccore/
        int idx = lower.indexOf("/nuccore/");
        if (idx >= 0) {
            String tail = s.substring(idx + "/nuccore/".length());
            int cut = indexOfAny(tail, '?', '/', '#');
            if (cut >= 0) tail = tail.substring(0, cut);
            tail = tail.trim();
            if (!tail.isEmpty()) accession = tail.toUpperCase(Locale.ROOT);
        }

        // id=... parameter (eutils-style URL)
        if (accession == null) {
            Matcher m = Pattern.compile("[?&]id=([^&#]+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                String v = m.group(1).trim();
                if (!v.isEmpty()) accession = v.toUpperCase(Locale.ROOT);
            }
        }

        // Otherwise, assume it's a raw accession-like token: NM_*/NG_* optionally with .version
        if (accession == null) {
            String u = s.toUpperCase(Locale.ROOT);
            if (u.matches("(NM|NG)_\\d+(\\.\\d+)?")) accession = u;
        }

        if (accession == null) return null;

        // Optional sub-range from the query string ("from/to" as on the website, or "seq_start/seq_stop").
        Integer from = queryInt(s, "from");
        if (from == null) from = queryInt(s, "seq_start");
        Integer to = queryInt(s, "to");
        if (to == null) to = queryInt(s, "seq_stop");
        // A partial range is meaningless; require both.
        if (from == null || to == null) {
            from = null;
            to = null;
        }
        return new AccRef(accession, from, to);
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static Integer queryInt(String url, String key) {
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(key) + "=(\\d+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Config parseArgs(String[] args) {
        Config c = new Config();
        int positional = 0;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    c.help = true;
                    break;
                case "-i":
                case "--in":
                    c.inputFile = Paths.get(requireValue(args, ++i, a));
                    break;
                case "-m":
                case "--input":
                    c.inputMode = parseInputMode(requireValue(args, ++i, a));
                    break;
                case "-t":
                case "--types":
                    c.types = parseTypes(requireValue(args, ++i, a));
                    break;
                case "--taxid":
                    c.taxId = requireValue(args, ++i, a);
                    break;
                case "--ng-from":
                    c.ngFrom = parseIntArg(a, requireValue(args, ++i, a));
                    break;
                case "--ng-to":
                    c.ngTo = parseIntArg(a, requireValue(args, ++i, a));
                    break;
                case "-o":
                case "--out":
                    c.outDir = Paths.get(requireValue(args, ++i, a));
                    break;
                case "--tool":
                    c.tool = requireValue(args, ++i, a);
                    break;
                case "--email":
                    c.email = requireValue(args, ++i, a);
                    break;
                case "--api-key":
                    c.apiKey = requireValue(args, ++i, a);
                    break;
                default:
                    // Backward-compatible positional form: file [taxid [ng-from [ng-to]]]
                    if (a != null && !a.startsWith("-")) {
                        switch (positional++) {
                            case 0 -> c.inputFile = Paths.get(a);
                            case 1 -> {
                                if (!a.matches("\\d+")) throw new IllegalArgumentException("Positional taxid must be numeric: " + a);
                                c.taxId = a;
                            }
                            case 2 -> c.ngFrom = parseIntArg("ng-from (positional)", a);
                            case 3 -> c.ngTo = parseIntArg("ng-to (positional)", a);
                            default -> throw new IllegalArgumentException("Too many positional arguments: " + a);
                        }
                    } else {
                        throw new IllegalArgumentException("Unknown argument: " + a);
                    }
            }
        }

        // A sub-range needs both ends; a half-specified range is almost always a mistake.
        if ((c.ngFrom == null) ^ (c.ngTo == null)) {
            throw new IllegalArgumentException("Both --ng-from and --ng-to must be specified together "
                    + "(got ng-from=" + c.ngFrom + ", ng-to=" + c.ngTo + ").");
        }
        if (c.ngFrom != null && c.ngTo != null && c.ngFrom > c.ngTo) {
            throw new IllegalArgumentException("--ng-from (" + c.ngFrom + ") must be <= --ng-to (" + c.ngTo + ").");
        }
        return c;
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        return args[idx];
    }

    private static int parseIntArg(String flag, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + flag + ": " + value);
        }
    }

    private static InputMode parseInputMode(String s) {
        String x = s.trim().toLowerCase(Locale.ROOT);
        return switch (x) {
            case "auto" -> InputMode.AUTO;
            case "genes", "gene", "symbols" -> InputMode.GENES;
            case "acc", "accession", "accessions" -> InputMode.ACCESSIONS;
            default -> throw new IllegalArgumentException("Unknown input mode: " + s + " (use auto|genes|acc)");
        };
    }

    private static EnumSet<RecordType> parseTypes(String s) {
        EnumSet<RecordType> out = EnumSet.noneOf(RecordType.class);
        for (String tok : s.split("[,;\\s]+")) {
            String x = tok.trim().toUpperCase(Locale.ROOT);
            if (x.isEmpty()) continue;
            if (x.equals("NM")) out.add(RecordType.NM);
            else if (x.equals("NG")) out.add(RecordType.NG);
            else throw new IllegalArgumentException("Unknown type: " + tok + " (use NM, NG, or NM,NG)");
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No valid record types in: " + s + " (use NM, NG, or NM,NG)");
        return out;
    }

    private static void printUsage() {
        System.out.println("NCBI RefSeq GenBank Downloader (Java)\n");
        System.out.println("Usage:");
        System.out.println("  java -jar NcbiRefSeqGenbankDownloader.jar --in <file> [--input auto|genes|acc] [--types NM,NG]");
        System.out.println("       [--taxid 9606] [--ng-from N --ng-to M] [--out outdir]");
        System.out.println("       [--tool NAME] [--email you@domain] [--api-key KEY]\n");
        System.out.println("Options:");
        System.out.println("  -i, --in <file>        Input file (required): one gene symbol or accession/URL per line");
        System.out.println("  -m, --input <mode>     auto (default) | genes | acc");
        System.out.println("  -t, --types <list>     NM | NG | NM,NG   (default: NM,NG)");
        System.out.println("      --taxid <id>       NCBI Taxonomy ID for gene mode (default: 9606, Homo sapiens)");
        System.out.println("      --ng-from <N>      Sub-range start (1-based) for NG_ records");
        System.out.println("      --ng-to <M>        Sub-range end   (1-based) for NG_ records");
        System.out.println("  -o, --out <dir>        Output directory (default: out)");
        System.out.println("      --tool <name>      Tool name sent to NCBI (default: env NCBI_TOOL or my_java_ncbi_tool)");
        System.out.println("      --email <addr>     Contact email sent to NCBI (default: env NCBI_EMAIL)");
        System.out.println("      --api-key <key>    NCBI API key for higher throughput (default: env NCBI_API_KEY)");
        System.out.println("  -h, --help             Show this help\n");
        System.out.println("Examples:");
        System.out.println("  # Gene symbols file, download both NM_ and NG_ (default) for human");
        System.out.println("  java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt --input genes --taxid 9606 --types NM,NG\n");
        System.out.println("  # Gene symbols file, download only RefSeqGene (NG_) for human");
        System.out.println("  java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt --input genes --taxid 9606 --types NG\n");
        System.out.println("  # Accessions file (NG_*/NM_* or NCBI nuccore URLs), download only NG_ with a range");
        System.out.println("  java -jar NcbiRefSeqGenbankDownloader.jar --in acc.txt --input acc --types NG --ng-from 13732 --ng-to 58896\n");
        System.out.println("Input file format:");
        System.out.println("  - one gene symbol or accession per line");
        System.out.println("  - supports comments starting with #");
        System.out.println("  - supports separators: whitespace, comma, semicolon\n");
        System.out.println("Environment variables:");
        System.out.println("  NCBI_TOOL, NCBI_EMAIL, NCBI_API_KEY\n");
    }

    // ---------- Step 1: GeneID ----------
    public Optional<String> findGeneId(String geneSymbol, String taxId) throws Exception {
        // gene symbol + organism (TaxID)
        String term = geneSymbol + "[Gene Name] AND txid" + taxId + "[Organism]";
        URI uri = uri("esearch.fcgi", Map.of(
                "db", "gene",
                "term", term,
                "retmode", "xml",
                "retmax", "5"
        ));
        Document doc = getXml(uri);
        List<String> ids = xpathText(doc, "//IdList/Id/text()");
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    // ---------- Step 2: ELink gene->nuccore, return accession.version ----------
    public List<String> elinkAccessionVersions(String geneId, String linkname) throws Exception {
        // idtype=acc => ELink returns accession.version strings
        URI uri = uri("elink.fcgi", Map.of(
                "dbfrom", "gene",
                "db", "nuccore",
                "id", geneId,
                "linkname", linkname,
                "idtype", "acc",
                "retmode", "xml"
        ));
        Document doc = getXml(uri);
        // In ELink XML this is: <Link><Id>ACC.V</Id></Link>
        return xpathText(doc, "//LinkSetDb/Link/Id/text()");
    }

    // ---------- Step 3: EFetch GenBank (gbwithparts) + (optional) range ----------
    public void efetchGenbank(String accver, Path outFile, Integer seqStart, Integer seqStop) throws IOException, InterruptedException {
        // GenBank flat file: rettype=gb or gbwithparts; we use gbwithparts
        Map<String, String> p = new LinkedHashMap<>();
        p.put("db", "nuccore");
        p.put("id", accver);
        p.put("rettype", "gbwithparts");
        p.put("retmode", "text");

        // 1-based range (like "from/to" on the website): seq_start/seq_stop
        if (seqStart != null && seqStop != null) {
            p.put("seq_start", String.valueOf(seqStart));
            p.put("seq_stop", String.valueOf(seqStop));
        }

        URI uri = uri("efetch.fcgi", p);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<byte[]> resp = sendWithRetry(req, HttpResponse.BodyHandlers.ofByteArray(), "EFetch " + accver);
        if (resp.statusCode() != 200) {
            throw new IOException("EFetch HTTP " + resp.statusCode() + " for " + accver + bodySnippet(resp.body()));
        }

        byte[] body = resp.body();
        // EFetch can return HTTP 200 with a plain-text error or an empty body (e.g. a withdrawn accession
        // or an out-of-range seq_start/seq_stop). Validate before committing anything to disk.
        if (!looksLikeGenBank(body)) {
            throw new IOException("EFetch for " + accver + " did not return a GenBank record." + bodySnippet(body));
        }

        // Write to a sibling temp file then move into place, so a previously good file is never
        // clobbered by a bad/partial download.
        Path tmp = outFile.resolveSibling(outFile.getFileName() + ".part");
        Files.write(tmp, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("  saved " + outFile.getFileName() + " (" + body.length + " bytes)");
    }

    // ---------- helpers ----------

    // Paces ALL E-utilities requests to stay under NCBI's rate cap (3 req/s without a key, 10 req/s with one),
    // measuring real elapsed time so esearch/elink/efetch all share one budget.
    private synchronized void throttle() {
        long minIntervalMs = (apiKey == null || apiKey.isBlank()) ? 350 : 110;
        if (lastRequestNanos != 0L) {
            long elapsedMs = (System.nanoTime() - lastRequestNanos) / 1_000_000L;
            long waitMs = minIntervalMs - elapsedMs;
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastRequestNanos = System.nanoTime();
    }

    // Sends a request with rate limiting and bounded retry/backoff on transient failures
    // (IOException/timeout and HTTP 429/5xx). Honors Retry-After when present.
    private <T> HttpResponse<T> sendWithRetry(HttpRequest req, HttpResponse.BodyHandler<T> handler, String what)
            throws IOException, InterruptedException {
        final int maxAttempts = 4;
        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throttle();
            HttpResponse<T> resp;
            try {
                resp = http.send(req, handler);
            } catch (IOException e) { // includes HttpTimeoutException / HttpConnectTimeoutException
                lastIo = e;
                if (attempt == maxAttempts) break;
                System.err.println("  " + what + ": " + e.getClass().getSimpleName()
                        + " (attempt " + attempt + "/" + maxAttempts + "), retrying...");
                backoff(attempt, -1L);
                continue;
            }
            int sc = resp.statusCode();
            if (sc == 200 || !isTransient(sc) || attempt == maxAttempts) {
                return resp; // success, or a non-transient/last-attempt status for the caller to handle
            }
            System.err.println("  " + what + ": HTTP " + sc
                    + " (attempt " + attempt + "/" + maxAttempts + "), retrying...");
            backoff(attempt, parseRetryAfterMs(resp));
        }
        throw (lastIo != null) ? lastIo
                : new IOException("Request failed after " + maxAttempts + " attempts: " + what);
    }

    private static boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static long parseRetryAfterMs(HttpResponse<?> resp) {
        return resp.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000L; // delta-seconds form
            } catch (NumberFormatException e) {
                return -1L; // HTTP-date form not handled; fall back to exponential backoff
            }
        }).orElse(-1L);
    }

    private static void backoff(int attempt, long retryAfterMs) throws InterruptedException {
        long waitMs = (retryAfterMs > 0) ? retryAfterMs : (long) (500L * Math.pow(2, attempt - 1));
        waitMs = Math.min(Math.max(waitMs, 0L), 8000L);
        Thread.sleep(waitMs);
    }

    private static boolean looksLikeGenBank(byte[] body) {
        if (body == null || body.length == 0) return false;
        int i = 0;
        while (i < body.length) {
            byte b = body[i];
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') i++;
            else break;
        }
        int n = Math.min(5, body.length - i);
        if (n < 5) return false;
        return new String(body, i, n, StandardCharsets.US_ASCII).equals("LOCUS");
    }

    private static String bodySnippet(byte[] body) {
        if (body == null || body.length == 0) return "";
        String s = new String(body, StandardCharsets.UTF_8);
        s = s.substring(0, Math.min(s.length(), 300)).strip();
        return s.isEmpty() ? "" : "\n  Body: " + s;
    }

    private URI uri(String endpoint, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>(params);
        // NCBI best practice: include tool + email; use api_key for higher request rates
        p.put("tool", tool);
        p.put("email", email);
        if (apiKey != null && !apiKey.isBlank()) {
            p.put("api_key", apiKey);
        }

        String qs = p.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(EUTILS + endpoint + "?" + qs);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private Document getXml(URI uri) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Accept", "application/xml")
                .header("User-Agent", tool + " (" + email + ")")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        // Read body as bytes so we can both validate Content-Type and parse
        HttpResponse<byte[]> resp = sendWithRetry(req, HttpResponse.BodyHandlers.ofByteArray(), "GET " + uri.getPath());

        String ct = resp.headers().firstValue("Content-Type").orElse("");
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + uri + bodySnippet(resp.body()));
        }

        // If HTML (or non-XML) arrives, show a short body snippet for debugging
        if (!ct.toLowerCase(Locale.ROOT).contains("xml")) {
            throw new IOException("Expected XML but got Content-Type: " + ct + bodySnippet(resp.body()));
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);

        // Secure processing
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        // Block external entities and external DTDs (XXE protection),
        // but DO NOT forbid DOCTYPE entirely (NCBI XML may include it).
        try {
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (Exception ignored) {
        }
        try {
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        try {
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
        }
        // IMPORTANT: do NOT set disallow-doctype-decl=true

        var builder = dbf.newDocumentBuilder();

        // Disable any DTD/ENTITY resolution attempts
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

        try (var in = new ByteArrayInputStream(resp.body())) {
            return builder.parse(in);
        }
    }

    private static List<String> xpathText(Document doc, String expr) throws Exception {
        var xp = XPathFactory.newInstance().newXPath();
        NodeList nl = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
        List<String> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            out.add(nl.item(i).getNodeValue());
        }
        return out;
    }
}
