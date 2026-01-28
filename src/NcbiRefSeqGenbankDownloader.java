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
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.regex.Pattern;

public class NcbiRefSeqGenbankDownloader {

    private static final String EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";

    private final String tool;
    private final String email;
    private final String apiKey; // may be null
    private final HttpClient http;

    public NcbiRefSeqGenbankDownloader(String tool, String email, String apiKey) {
        this.tool = Objects.requireNonNull(tool);
        this.email = Objects.requireNonNull(email);
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    // Reads a simple list file. Supports: comments (#...), blank lines, and separators (, ; whitespace).
    static List<String> readListFile(Path path) throws Exception {
        Pattern split = Pattern.compile("[,;\\s]+"); // whitespace/tab/comma/semicolon
        LinkedHashSet<String> out = new LinkedHashSet<>();

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }

            for (String tok : split.split(s)) {
                String gene = tok.trim();
                if (gene.isEmpty()) {
                    continue;
                }
                // basic normalization: gene symbols/accessions are typically uppercase
                out.add(gene.toUpperCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(out);
    }

    enum InputMode { AUTO, GENES, ACCESSIONS }
    enum RecordType { NM, NG }

    static final class Config {
        Path inputFile;
        InputMode inputMode = InputMode.AUTO;
        EnumSet<RecordType> types = EnumSet.noneOf(RecordType.class); // empty => use defaults
        String taxId = "9606"; // Homo sapiens by default
        Integer ngFrom = null;
        Integer ngTo = null;
        Path outDir = Paths.get("out");

        String tool = System.getenv().getOrDefault("NCBI_TOOL", "my_java_ncbi_tool");
        String email = System.getenv().getOrDefault("NCBI_EMAIL", "youremail@example.org");
        String apiKey = System.getenv("NCBI_API_KEY");

        boolean help = false;
    }

    public static void main(String[] args) throws Exception {
        Config cfg = parseArgs(args);

        if (cfg.help) {
            printUsage();
            return;
        }
        if (cfg.inputFile == null) {
            System.err.println("Input file is required. Use --in <file> or -i <file>.");
            printUsage();
            return;
        }

        List<String> items = readListFile(cfg.inputFile);
        if (items.isEmpty()) {
            System.err.println("Input file is empty: " + cfg.inputFile);
            return;
        }

        // Determine input mode if AUTO
        InputMode mode = cfg.inputMode;
        if (mode == InputMode.AUTO) {
            mode = looksLikeAccession(items) ? InputMode.ACCESSIONS : InputMode.GENES;
        }

        // Defaults for record types:
        // - GENES: download both NM and NG (as in earlier versions)
        // - ACCESSIONS: download what is present, but still filter by requested types if set
        EnumSet<RecordType> types = cfg.types.isEmpty()
                ? (mode == InputMode.GENES ? EnumSet.of(RecordType.NM, RecordType.NG) : EnumSet.of(RecordType.NM, RecordType.NG))
                : cfg.types;

        var dl = new NcbiRefSeqGenbankDownloader(cfg.tool, cfg.email, cfg.apiKey);
        Files.createDirectories(cfg.outDir);

        if (mode == InputMode.ACCESSIONS) {
            runAccessionMode(dl, items, cfg, types);
        } else {
            runGeneMode(dl, items, cfg, types);
        }
    }

    private static void runGeneMode(NcbiRefSeqGenbankDownloader dl, List<String> genes, Config cfg, EnumSet<RecordType> types) throws Exception {
        System.out.println("Input mode: GENES (symbols)");
        System.out.println("TaxID: " + cfg.taxId);
        System.out.println("Download types: " + types);
        if (types.contains(RecordType.NG) && cfg.ngFrom != null && cfg.ngTo != null) {
            System.out.println("NG_ range: " + cfg.ngFrom + ".." + cfg.ngTo + " (1-based, inclusive)");
        }

        for (String geneSymbol : genes) {
            System.out.println("== " + geneSymbol + " ==");
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
                for (String acc : nmAccs) {
                    dl.efetchGenbank(acc, geneOut.resolve(acc + ".gb"), null, null);
                    dl.politeDelay();
                }
            }

            if (types.contains(RecordType.NG)) {
                List<String> geneAccs = dl.elinkAccessionVersions(geneId.get(), "gene_nuccore_refseqgene");
                List<String> ngAccs = geneAccs.stream().map(String::trim).filter(a -> a.startsWith("NG_")).distinct().toList();
                System.out.println("  NG_=" + ngAccs.size());
                for (String acc : ngAccs) {
                    dl.efetchGenbank(acc, geneOut.resolve(acc + ".gb"), cfg.ngFrom, cfg.ngTo);
                    dl.politeDelay();
                }
            }
        }
    }

    private static void runAccessionMode(NcbiRefSeqGenbankDownloader dl, List<String> rawItems, Config cfg, EnumSet<RecordType> types) throws Exception {
        System.out.println("Input mode: ACCESSIONS (ACC.V or NCBI URLs)");
        System.out.println("Download types: " + types);
        if (types.contains(RecordType.NG) && cfg.ngFrom != null && cfg.ngTo != null) {
            System.out.println("NG_ range: " + cfg.ngFrom + ".." + cfg.ngTo + " (1-based, inclusive)");
        }

        Path out = cfg.outDir.resolve("accessions");
        Files.createDirectories(out);

        List<String> accs = new ArrayList<>();
        for (String item : rawItems) {
            String acc = extractAccession(item);
            if (acc != null && !acc.isBlank()) {
                accs.add(acc);
            }
        }

        // Filter by requested types
        List<String> filtered = accs.stream()
                .map(String::trim)
                .filter(a -> {
                    boolean isNM = a.startsWith("NM_");
                    boolean isNG = a.startsWith("NG_");
                    if (isNM) return types.contains(RecordType.NM);
                    if (isNG) return types.contains(RecordType.NG);
                    return false; // ignore non-NM/NG accessions in this tool
                })
                .distinct()
                .toList();

        System.out.println("Accessions to download: " + filtered.size());
        for (String acc : filtered) {
            Path outFile = out.resolve(acc + ".gb");
            Integer start = null, stop = null;
            if (acc.startsWith("NG_") && cfg.ngFrom != null && cfg.ngTo != null) {
                start = cfg.ngFrom;
                stop = cfg.ngTo;
            }
            dl.efetchGenbank(acc, outFile, start, stop);
            dl.politeDelay();
        }
    }

    private static boolean looksLikeAccession(List<String> items) {
        for (String s : items) {
            String x = s.trim();
            if (x.isEmpty() || x.startsWith("#")) continue;
            String acc = extractAccession(x);
            if (acc == null) continue;
            if (acc.startsWith("NM_") || acc.startsWith("NG_")) return true;
        }
        return false;
    }

    // Extract accession.version from a raw token or an NCBI nuccore URL.
    // Examples:
    //   NG_008847.2
    //   https://www.ncbi.nlm.nih.gov/nuccore/NG_008847.2?from=...
    // Returns null if nothing looks like an accession.
    private static String extractAccession(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // If it's a nuccore URL: take path segment after /nuccore/
        int idx = s.indexOf("/nuccore/");
        if (idx >= 0) {
            String tail = s.substring(idx + "/nuccore/".length());
            int q = tail.indexOf('?');
            if (q >= 0) tail = tail.substring(0, q);
            tail = tail.trim();
            if (!tail.isEmpty()) return tail.toUpperCase(Locale.ROOT);
        }

        // If has id=... parameter
        int idp = s.toLowerCase(Locale.ROOT).indexOf("id=");
        if (idp >= 0) {
            String tail = s.substring(idp + 3);
            int amp = tail.indexOf('&');
            if (amp >= 0) tail = tail.substring(0, amp);
            tail = tail.trim();
            if (!tail.isEmpty()) return tail.toUpperCase(Locale.ROOT);
        }

        // Otherwise, assume it's a raw accession-like token
        // We accept NM_*/NG_* optionally with .version
        String u = s.toUpperCase(Locale.ROOT);
        if (u.matches("(NM|NG)_\\d+(\\.\\d+)?")) return u;
        return null;
    }

    private static Config parseArgs(String[] args) {
        Config c = new Config();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    c.help = true;
                    break;
                case "-i":
                case "--in":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.inputFile = Paths.get(args[++i]);
                    break;
                case "-m":
                case "--input":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.inputMode = parseInputMode(args[++i]);
                    break;
                case "-t":
                case "--types":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.types = parseTypes(args[++i]);
                    break;
                case "--taxid":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.taxId = args[++i];
                    break;
                case "--ng-from":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.ngFrom = Integer.valueOf(args[++i]);
                    break;
                case "--ng-to":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.ngTo = Integer.valueOf(args[++i]);
                    break;
                case "-o":
                case "--out":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.outDir = Paths.get(args[++i]);
                    break;
                case "--tool":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.tool = args[++i];
                    break;
                case "--email":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.email = args[++i];
                    break;
                case "--api-key":
                    if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                    c.apiKey = args[++i];
                    break;
                default:
                    // Backward compatibility with the old positional mode:
                    //   args[0]=file, args[1]=taxid, args[2]=ngFrom, args[3]=ngTo
                    // If user passes positional arguments without flags.
                    if (a != null && !a.startsWith("-")) {
                        if (c.inputFile == null) {
                            c.inputFile = Paths.get(a);
                        } else if ("9606".equals(c.taxId) && c.taxId.equals("9606")) {
                            // If taxid still default and looks numeric, treat as taxid
                            if (a.matches("\\d+")) c.taxId = a;
                        } else if (c.ngFrom == null && a.matches("\\d+")) {
                            c.ngFrom = Integer.valueOf(a);
                        } else if (c.ngTo == null && a.matches("\\d+")) {
                            c.ngTo = Integer.valueOf(a);
                        }
                    } else {
                        throw new IllegalArgumentException("Unknown argument: " + a);
                    }
            }
        }

        // If only one of ngFrom/ngTo is set, ignore both (avoid ambiguous partial range)
        if ((c.ngFrom == null) ^ (c.ngTo == null)) {
            System.err.println("Warning: both --ng-from and --ng-to must be set. Ignoring range.");
            c.ngFrom = null;
            c.ngTo = null;
        }
        return c;
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
        return out;
    }

    private static void printUsage() {
        System.out.println("NCBI RefSeq GenBank Downloader (Java)\n");
        System.out.println("Usage:");
        System.out.println("  java NcbiRefSeqGenbankDownloader --in <file> [--input auto|genes|acc] [--types NM,NG] [--taxid 9606] [--ng-from N --ng-to M] [--out outdir]\n");
        System.out.println("Examples:");
        System.out.println("  # Gene symbols file, download both NM_ and NG_ (default) for human");
        System.out.println("  java NcbiRefSeqGenbankDownloader --in genes.txt --input genes --taxid 9606 --types NM,NG\n");
        System.out.println("  # Gene symbols file, download only RefSeqGene (NG_) for human");
        System.out.println("  java NcbiRefSeqGenbankDownloader --in genes.txt --input genes --taxid 9606 --types NG\n");
        System.out.println("  # Accessions file (NG_*/NM_* or NCBI nuccore URLs), download only NG_ with a range\njava -jar NcbiRefSeqGenbankDownloader.jar --in acc.txt --input acc --types NG --ng-from 13732 --ng-to 58896\n");
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
    public void efetchGenbank(String accver, Path outFile, Integer seqStart, Integer seqStop) throws Exception {
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

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("EFetch HTTP " + resp.statusCode() + " for " + accver);
        }

        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            in.transferTo(out);
        }
    }

    // ---------- helpers ----------
    private void politeDelay() {
        try {
            Thread.sleep((apiKey == null || apiKey.isBlank()) ? 400 : 150);
        } catch (InterruptedException ignored) {
        }
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
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

        String ct = resp.headers().firstValue("Content-Type").orElse("");
        if (resp.statusCode() != 200) {
            String snippet = new String(resp.body(), StandardCharsets.UTF_8);
            snippet = snippet.substring(0, Math.min(snippet.length(), 400));
            throw new IOException("HTTP " + resp.statusCode() + " for " + uri + "\nBody: " + snippet);
        }

        // If HTML (or non-XML) arrives, show a short body snippet for debugging
        if (!ct.toLowerCase(Locale.ROOT).contains("xml")) {
            String snippet = new String(resp.body(), StandardCharsets.UTF_8);
            snippet = snippet.substring(0, Math.min(snippet.length(), 400));
            throw new IOException("Expected XML but got Content-Type: " + ct + "\nBody: " + snippet);
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
