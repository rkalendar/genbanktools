# NCBI RefSeq GenBank Downloader

**A Java CLI utility for batch downloading annotated GenBank flat files from NCBI**


👤 **Author:** Ruslan Kalendar
📧 **Contact:** ruslan.kalendar@helsinki.fi


[![Java](https://img.shields.io/badge/Java-25+-orange.svg)](https://www.oracle.com/java/technologies/downloads/)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-blue.svg)]()
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)


---

## Table of Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Build](#build)
- [Quick Start](#quick-start)
- [Input Modes](#input-modes)
- [CLI Options](#cli-options)
- [Environment Variables](#environment-variables)
- [Examples](#examples)
- [Output Layout](#output-layout)
- [Companion Utilities](#companion-utilities)
- [NCBI Usage Notes](#ncbi-usage-notes)
- [License](#license)

---

## Overview

This tool downloads **GenBank flat files** (`rettype=gbwithparts`, `retmode=text`) — including the full `FEATURES` table (exons, introns, CDS, and other annotated elements) — from NCBI. It supports two input modes:

| Mode | Input | Workflow |
|------|-------|----------|
| **Gene symbols** | `BRCA2`, `TP53`, … | Resolves GeneID → fetches linked RefSeq accessions → downloads GenBank records |
| **Accessions / URLs** | `NM_000546.6`, `NG_008847.2`, nuccore URLs | Downloads GenBank records directly (no GeneID lookup) |

### Why This Tool?

- **Batch downloads** of RefSeq mRNA (NM_) and/or RefSeqGene genomic (NG_) records
- Uses official NCBI **Entrez E-utilities** (ESearch, ELink, EFetch)
- Produces GenBank flat files identical to NCBI nuccore `report=genbank`
- Supports **sub-range extraction** for NG_ records and **nuccore URL parsing** (accession and `from/to` range)
- **Resilient batch runs**: shared rate limiting across all E-utility calls, automatic retry with back-off on transient errors (HTTP 429/5xx, timeouts), per-item failure isolation (one bad record does not abort the whole run), and validation that each saved file is a real GenBank record

---

## Requirements

| Requirement | Details |
|-------------|---------|
| **Java** | Version 25 or higher (JRE to run, JDK to build) |
| **OS** | Windows, Linux, or macOS |
| **Network** | Internet access required |
| **Optional** | NCBI API key (recommended for higher throughput) |

**Download Java:** https://www.oracle.com/java/technologies/downloads/
**Set Java Path:** https://www.java.com/en/download/help/path.html

> The code uses only standard-library features available since Java 16/17 (`java.net.http.HttpClient`, `Stream.toList()`, switch expressions). No third-party dependencies.

---

## Build

The project is a NetBeans/Ant project. The main class is `NcbiRefSeqGenbankDownloader`.

```bash
# Option A: NetBeans/Ant (produces dist/NcbiRefSeqGenbankDownloader.jar)
ant clean jar

# Option B: plain javac (no build tool)
javac -d build/classes src/NcbiRefSeqGenbankDownloader.java
java  -cp build/classes NcbiRefSeqGenbankDownloader --help
```

The examples below assume the runnable jar from Option A.

---

## Quick Start

```bash
# Download GenBank files for a list of genes (input mode auto-detected)
java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt

# Show help
java -jar NcbiRefSeqGenbankDownloader.jar --help
```

> **Set a contact email.** NCBI E-utilities policy asks for a valid contact address. If you do not
> set one, the tool warns and sends a placeholder. Provide yours with `--email you@domain` or the
> `NCBI_EMAIL` environment variable (see [Environment Variables](#environment-variables)).

---

## Input Modes

The input mode is chosen with `--input` (alias `-m`). When omitted it defaults to **`auto`**, which
inspects the file content: if any line looks like an `NM_`/`NG_` accession or nuccore URL, the file
is treated as accessions; otherwise as gene symbols.

### Gene Symbols (`--input genes`)

For each gene symbol, the tool:

1. Finds the corresponding **NCBI GeneID** for the specified organism (via TaxID)
2. Retrieves linked RefSeq accessions via E-utilities:
   - **NM_\*** (RefSeq mRNA) from `gene_nuccore_refseqrna`
   - **NG_\*** (RefSeqGene genomic) from `gene_nuccore_refseqgene`
3. Downloads full GenBank flat files with the complete `FEATURES` table
4. Optionally extracts a **sub-range** for NG_ records (`--ng-from` / `--ng-to`)

**Example `genes.txt`:**

```
# Human cancer genes
BRCA2
TP53
EGFR
KRAS

# Comma/space separators are also supported
BRCA1, PIK3CA MYC
```

### Accessions / URLs (`--input acc`)

Accepts `NM_` and/or `NG_` accession strings, as well as full nuccore (and E-utilities) URLs. From a
URL the tool extracts the accession and, if present, the `from`/`to` (or `seq_start`/`seq_stop`)
sub-range. A per-URL range takes precedence; otherwise `--ng-from`/`--ng-to` apply to all `NG_`
records in the run.

**Example `acc.txt`:**

```
# Plain accessions
NG_008847.2
NM_000546.6

# nuccore URL with an inline range (downloaded as bases 13732..58896)
https://www.ncbi.nlm.nih.gov/nuccore/NG_008847.2?from=13732&to=58896&report=genbank
```

---

## CLI Options

### Input

| Option | Description |
|--------|-------------|
| `-i`, `--in <file>` | Path to the input file **(required)** |
| `-m`, `--input genes\|acc\|auto` | Input interpretation: `genes` = gene symbols, `acc` = accessions/URLs, `auto` = auto-detect (**default**) |

### Record Types

| Option | Description |
|--------|-------------|
| `-t`, `--types NM` | Download NM_ only (RefSeq mRNA) |
| `-t`, `--types NG` | Download NG_ only (RefSeqGene genomic) |
| `-t`, `--types NM,NG` | Download both |

> If `--types` is omitted, the default is **`NM,NG`** (both) — in gene mode and accession mode alike.

### Organism Filter (gene symbol mode only)

| Option | Description |
|--------|-------------|
| `--taxid <TaxID>` | NCBI Taxonomy ID (default: `9606` — *Homo sapiens*) |

### Sub-range for NG_ Records (optional)

| Option | Description |
|--------|-------------|
| `--ng-from <N>` | Start coordinate (1-based) — must be given together with `--ng-to` |
| `--ng-to <M>` | End coordinate (1-based), `N ≤ M` |

### Output & NCBI Identification

| Option | Description |
|--------|-------------|
| `-o`, `--out <dir>` | Output directory (default: `out`) |
| `--tool <name>` | Tool name sent to NCBI (default: env `NCBI_TOOL`, else `my_java_ncbi_tool`) |
| `--email <addr>` | Contact email sent to NCBI (default: env `NCBI_EMAIL`, else a placeholder) |
| `--api-key <key>` | NCBI API key for higher throughput (default: env `NCBI_API_KEY`) |
| `-h`, `--help` | Show usage |

---

## Environment Variables

These provide defaults for the corresponding options (the command-line flag wins if both are set):

| Variable | Equivalent flag | Purpose |
|----------|-----------------|---------|
| `NCBI_TOOL` | `--tool` | Tool name reported to NCBI |
| `NCBI_EMAIL` | `--email` | Contact email reported to NCBI |
| `NCBI_API_KEY` | `--api-key` | API key for higher rate limits (~10 req/s vs ~3 req/s) |

```bash
export NCBI_EMAIL="you@example.org"
export NCBI_API_KEY="your_ncbi_api_key"
java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt
```

---

## Examples

### Download RefSeqGene (NG_) for a list of human genes

```bash
java -jar NcbiRefSeqGenbankDownloader.jar \
    --in genes.txt --input genes --types NG --taxid 9606
```

### Download RefSeq mRNA (NM_) only

```bash
java -jar NcbiRefSeqGenbankDownloader.jar \
    --in genes.txt --input genes --types NM --taxid 9606
```

### Download from accessions/URLs with a sub-range

```bash
java -jar NcbiRefSeqGenbankDownloader.jar \
    --in acc.txt --input acc --types NG --ng-from 13732 --ng-to 58896
```

### Auto-detect input, download both NM_ and NG_

```bash
java -jar NcbiRefSeqGenbankDownloader.jar \
    --in input.txt --types NM,NG
```

---

## Output Layout

```
out/
├── BRCA2/                    # gene mode
│   ├── NM_000059.4.gb
│   └── NG_012772.3.gb
├── TP53/
│   └── NM_000546.6.gb
└── accessions/               # accession/URL mode
    ├── NG_008847.2.gb
    └── NM_000546.6.gb
```

| Input Mode | Output Path |
|------------|-------------|
| gene symbols | `out/<GENE_SYMBOL>/<ACCESSION>.gb` |
| accessions/URLs | `out/accessions/<ACCESSION>.gb` |

> Files are written atomically (a temporary `*.part` file is moved into place only after the
> download validates as a GenBank record), so an interrupted or failed download never leaves a
> truncated `.gb` and never clobbers a previously good one. When a sub-range is applied, the file
> keeps the plain `<ACCESSION>.gb` name.

### Suggested `.gitignore`

```gitignore
out/
*.gb
```

---

## Companion Utilities

The `src/` directory also contains small standalone helper programs (each with its own `main`).
They are not part of the downloader jar — run them from the compiled classes, e.g.
`java -cp build/classes <ClassName> …`.

### `GenBankMrnaExonExtractor`

Extracts exon coordinates from the `mRNA` feature(s) of downloaded **NG_** genomic GenBank files —
the natural next step after downloading. For each mRNA feature it prints the location, strand, every
exon's 1-based genomic coordinates and length, and the total exon length. Exons are numbered in
biological 5'→3' transcript order (on the minus strand the highest-coordinate segment is exon 1).

> Intended for NG_ genomic records (whose mRNA features carry a `join(...)` of exon segments). It is
> not designed for NM_/NR_ transcript records, whose exons are separate `exon` features in
> transcript-relative coordinates; running it on those yields no output.

```bash
# args: [dir] [glob]   (defaults: "." and "NG_*.gb*")
java -cp build/classes GenBankMrnaExonExtractor out/BRCA2 "NG_*.gb"
```

### `BatchReadFiles`

Recursively walks a root directory and prints the first 5 lines of every file matching a glob
(lenient UTF-8 decoding, so a stray binary file does not abort the scan).

```bash
# args: [rootDir] [glob]   (defaults: "." and "*.*")
java -cp build/classes BatchReadFiles out "*.gb"
```

### `BatchOpenFiles`

Opens the first file matching a glob in each immediate sub-directory of a root, using the desktop's
default application (`java.awt.Desktop`), optionally pausing for Enter between files.

```bash
# args: <rootDir> [glob] [pauseBetween=true|false]   (glob default: *.pdf)
java -cp build/classes BatchOpenFiles out "*.pdf" false
```

---

## NCBI Usage Notes

- The tool automatically attaches `tool` and `email` to every E-utilities request. Set a **real
  email** (`--email` / `NCBI_EMAIL`) — NCBI uses it to contact you before throttling or blocking; by
  default a placeholder is sent and the tool warns about it.
- **Rate limits:** ~3 requests/sec without an API key, ~10 requests/sec with one. The tool paces all
  ESearch/ELink/EFetch calls under this cap and backs off automatically on `429`/`5xx` responses
  (honoring `Retry-After` when provided).
- See the [NCBI E-utilities documentation](https://www.ncbi.nlm.nih.gov/books/NBK25497/) for details.

---

## License

This project is licensed under the **GNU General Public License v3.0** (GPL-3.0).
See https://www.gnu.org/licenses/gpl-3.0 for the full text.

© 2026 Ruslan Kalendar
