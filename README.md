# NCBI RefSeq GenBank Downloader (Java)
A small Java CLI utility to download **annotated GenBank flat files** from NCBI by **gene symbol list**.

## Availability and requirements:
Operating system(s): Cross-platform (Windows, Linux, macOS)

Programming language: Java 25 or higher
Java Downloads: https://www.oracle.com/java/technologies/downloads/

How do I set or change the Java path system variable: https://www.java.com/en/download/help/path.html


For each input gene symbol (e.g., `BRCA2`, `TP53`) the tool:
1. Finds the corresponding **NCBI GeneID** for a given organism (recommended: **TaxID**).
2. Retrieves linked RefSeq accessions via NCBI E-utilities:
   - **NM_*** (RefSeq mRNA) from `gene_nuccore_refseqrna`
   - **NG_*** (RefSeqGene genomic records) from `gene_nuccore_refseqgene`
3. Downloads full **GenBank flat files** (`rettype=gbwithparts`, `retmode=text`) that include the `FEATURES` table (exons, introns, CDS, misc features, etc., as provided by NCBI).
4. Optionally downloads a **sub-range** for RefSeqGene (NG_*) using `seq_start/seq_stop` (1-based coordinates).

Output is written into `out/<GENE_SYMBOL>/` with one `.gb` file per accession.

---

## Why this tool?

- Batch download of **RefSeq mRNA (NM_)** and **RefSeqGene genomic (NG_)** from gene symbols
- Uses official NCBI **Entrez E-utilities** endpoints (ESearch, ELink, EFetch)
- Produces GenBank flat files comparable to NCBI nuccore `report=genbank`

---

## Requirements

- Java **25+** (uses `java.net.http.HttpClient`)
- Internet access
- Recommended: NCBI API key for higher throughput (optional)

NCBI usage best practices:
- Provide `tool` and `email` parameters
- Respect rate limits (≈3 requests/sec without `api_key`; higher with `api_key`)

---

## Run
java NcbiRefSeqGenbankDownloader genes.txt

## Specify organism TaxID:

## Input format
java NcbiRefSeqGenbankDownloader genes.txt 9606

## Arguments:
args[0] — path to gene list file (required for batch mode)
args[1] — TaxID (default: 9606)
args[2] — NG_* seq_start (optional)
args[3] — NG_* seq_stop (optional)

Create a text file with gene symbols (one per line). Empty lines and comments are allowed.

## Output
Files are saved under:
out/<GENE_SYMBOL>/
  NM_XXXXXX.Y.gb
  NG_XXXXXX.Y.gb


Example `genes.txt`:
```txt
# Human cancer genes
BRCA2
TP53
EGFR
KRAS

# Separators like comma/space are also supported
BRCA1, PIK3CA MYC


