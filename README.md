# NCBI RefSeq GenBank Downloader

**A Java CLI utility for batch downloading annotated GenBank flat files from NCBI**

[![Java](https://img.shields.io/badge/Java-25+-orange.svg)](https://www.oracle.com/java/technologies/downloads/)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-blue.svg)]()

---

## Table of Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Input Modes](#input-modes)
- [CLI Options](#cli-options)
- [Examples](#examples)
- [Output Layout](#output-layout)
- [NCBI Usage Notes](#ncbi-usage-notes)

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
- Supports **sub-range extraction** for NG_ records and **nuccore URL parsing**

---

## Requirements

| Requirement | Details |
|-------------|---------|
| **Java** | Version 25 or higher |
| **OS** | Windows, Linux, or macOS |
| **Network** | Internet access required |
| **Optional** | NCBI API key (recommended for higher throughput) |

**Download Java:** https://www.oracle.com/java/technologies/downloads/
**Set Java Path:** https://www.java.com/en/download/help/path.html

---

## Quick Start

```bash
# Download GenBank files for a list of genes
java -jar NcbiRefSeqGenbankDownloader.jar --in genes.txt

# Show help
java -jar NcbiRefSeqGenbankDownloader.jar --help
```

---

## Input Modes

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

Accepts NM_ and/or NG_ accession strings, as well as full nuccore URLs (the tool extracts the accession and optional `from/to` range automatically).

**Example `acc.txt`:**

```
# Plain accessions
NG_008847.2
NM_000546.6

# nuccore URL with optional range
https://www.ncbi.nlm.nih.gov/nuccore/NG_008847.2?from=13732&to=58896&report=genbank
```

---

## CLI Options

### Input

| Option | Description |
|--------|-------------|
| `--in <file>` | Path to the input file **(required)** |
| `--input genes\|acc\|auto` | Input interpretation: `genes` = gene symbols, `acc` = accessions/URLs, `auto` = auto-detect from file content |

### Record Types

| Option | Description |
|--------|-------------|
| `--types NM` | Download NM_ only (RefSeq mRNA) |
| `--types NG` | Download NG_ only (RefSeqGene genomic) |
| `--types NM,NG` | Download both |

### Organism Filter (gene symbol mode only)

| Option | Description |
|--------|-------------|
| `--taxid <TaxID>` | NCBI Taxonomy ID (default: `9606` — *Homo sapiens*) |

### Sub-range for NG_ Records (optional)

| Option | Description |
|--------|-------------|
| `--ng-from <N>` | Start coordinate (1-based) |
| `--ng-to <M>` | End coordinate (1-based) |

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
    --in input.txt --input auto --types NM,NG
```

---

## Output Layout

```
out/
├── BRCA2/                    # --input genes
│   ├── NM_000059.4.gb
│   └── NG_012772.3.gb
├── TP53/
│   └── NM_000546.6.gb
└── accessions/               # --input acc
    ├── NG_008847.2.gb
    └── NM_000546.6.gb
```

| Input Mode | Output Path |
|------------|-------------|
| `--input genes` | `out/<GENE_SYMBOL>/<ACCESSION>.gb` |
| `--input acc` | `out/accessions/<ACCESSION>.gb` |

### Suggested `.gitignore`

```gitignore
out/
*.gb
```

---

## NCBI Usage Notes

- Provide `tool` and `email` parameters when calling E-utilities (handled by this tool)
- Rate limits: **~3 requests/sec** without an API key; higher throughput with `api_key`
- See [NCBI E-utilities documentation](https://www.ncbi.nlm.nih.gov/books/NBK25497/) for details

---

## License

*Add a `LICENSE` file (e.g., MIT) appropriate for your project.*
