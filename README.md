# NCBI RefSeq GenBank Downloader (Java)

A small Java **CLI** utility to download **annotated GenBank flat files** from NCBI using either:

- a **gene symbol list** (e.g., `BRCA2`, `TP53`), **or**
- a list of **accessions / nuccore URLs** (e.g., `NM_000546.6`, `NG_008847.2`, or a nuccore link)

The tool fetches **GenBank flat files** (`rettype=gbwithparts`, `retmode=text`) including the `FEATURES` table (exons, introns, CDS, and other annotated elements as provided by NCBI).

---

## Availability and requirements

- **Operating system(s):** Cross-platform (Windows, Linux, macOS)
- **Programming language:** Java **25+**
- **Network:** Internet access required
- **Optional:** NCBI API key (recommended for higher throughput)

Java downloads and PATH instructions:

```text
Java Downloads (Oracle): https://www.oracle.com/java/technologies/downloads/
How to set/change JAVA_HOME / PATH: https://www.java.com/en/download/help/path.html
```

---

## What it does

Depending on the selected input mode:

### Input mode: gene symbols (`--input genes`)
For each gene symbol (e.g., `BRCA2`, `TP53`) the tool:
1. Finds the corresponding **NCBI GeneID** for a given organism (recommended: **TaxID**).
2. Retrieves linked RefSeq accessions via NCBI E-utilities:
   - **NM_*** (RefSeq mRNA) from `gene_nuccore_refseqrna`
   - **NG_*** (RefSeqGene genomic records) from `gene_nuccore_refseqgene`
3. Downloads full **GenBank flat files** (`gbwithparts`) that include the `FEATURES` table.
4. Optionally downloads a **sub-range** for RefSeqGene (NG_*) using `seq_start/seq_stop` (1-based coordinates).

### Input mode: accessions / URLs (`--input acc`)
- Accepts **NM_*** and/or **NG_*** accession strings (e.g., `NG_008847.2`)
- Also accepts **nuccore URLs** (the tool extracts the accession and optional `from/to` if present)
- Downloads GenBank records directly **without** GeneID lookup

---

## Why this tool?

- Batch download of **RefSeq mRNA (NM_)** and/or **RefSeqGene genomic (NG_)**
- Uses official NCBI **Entrez E-utilities** endpoints (ESearch, ELink, EFetch)
- Produces GenBank flat files comparable to NCBI nuccore **`report=genbank`**

---

## NCBI usage notes (recommended)

- Provide `tool` and `email` parameters when calling E-utilities.
- Respect rate limits (**~3 requests/sec without** `api_key`; higher with `api_key`).

---

## Input files

### Gene symbols file example (`genes.txt`)
```txt
# Human cancer genes
BRCA2
TP53
EGFR
KRAS

# Separators like comma/space are supported
BRCA1, PIK3CA MYC
```

### Accessions / URLs file example (`acc.txt`)
```txt
# Accessions
NG_008847.2
NM_000546.6

# nuccore URL with optional range
https://www.ncbi.nlm.nih.gov/nuccore/NG_008847.2?from=13732&to=58896&report=genbank
```

---

## Quick start (run)

```bash
java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt
```

Show help:

```bash
java -jar NcbiRefSeqGenbankDownloader.jar --help
```

---

## CLI options

### Input selection
- `--in <file>`  
  Path to the input file (required)

- `--input genes|acc|auto`  
  Input interpretation:
  - `genes` — gene symbols (BRCA2, TP53, …)
  - `acc` — accessions (NM_/NG_) and/or nuccore URLs
  - `auto` — auto-detection based on file content

### What to download
- `--types NM|NG|NM,NG`  
  Select record types:
  - `NM` — NM_* only (RefSeq mRNA)
  - `NG` — NG_* only (RefSeqGene)
  - `NM,NG` — both

### Organism filter (used in `--input genes` mode)
- `--taxid <TaxID>`  
  Organism TaxID (default: `9606` for Homo sapiens)

### Range for NG_* (optional)
- `--ng-from <N> --ng-to <M>`  
  Applies only to **NG_*** downloads (1-based coordinates, like `from/to` on NCBI)

---

## Examples

### A) Input = gene symbols, download only RefSeqGene (NG_)
```bash
java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt --input genes --types NG --taxid 9606
```

### B) Input = gene symbols, download only NM_
```bash
java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt --input genes --types NM --taxid 9606
```

### C) Input = accessions/URLs, download only NG_ with a sequence range
`acc.txt` can contain both `NG_008847.2` and nuccore URLs like:
```text
https://www.ncbi.nlm.nih.gov/nuccore/NG_008847.2?from=13732&to=58896&report=genbank
```

Run:
```bash
java -jar NcbiRefSeqGenbankDownloader.jar --in acc.txt --input acc --types NG --ng-from 13732 --ng-to 58896
```

### D) Auto-detect input mode, download both NM_ and NG_
```bash
java -jar NcbiRefSeqGenbankDownloader.jar --in input.txt --input auto --types NM,NG
```

---

## Output layout

- If `--input genes`:  
  `out/<GENE_SYMBOL>/<ACCESSION>.gb`

- If `--input acc`:  
  `out/accessions/<ACCESSION>.gb`

---

## Suggested `.gitignore`

GenBank outputs are usually not committed:

```gitignore
out/
*.gb
```

---

## License

Add a `LICENSE` file (e.g., MIT) appropriate for your project.
