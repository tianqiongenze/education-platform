# DDL Template Instantiation Guide

> **D-099**: SP-02 Data Middle-Platform DDL templates extracted for industry-agnostic reuse.

## Overview

The SP-02 data platform ships two template files that can be instantiated for any industry:

| Template | Source (education) | Purpose |
|----------|-------------------|---------|
| `ods-template.sql` | `iceberg-ddl/ods-tables.sql` | Operational Data Store — CDC row images from TiDB |
| `dws-template.sql` | `iceberg-ddl/dws-tables.sql` | Data Warehouse Service — Flink streaming aggregations |

## What Changed (D-099)

The original education-specific DDL used column names like `course_id` and table names like `ods_learning_event`. The templates generalise these:

| Education (original) | Template (generic) | Rationale |
|----------------------|-------------------|-----------|
| `course_id` | `product_id` | Order line item is not always a course |
| `ods_learning_event` | `ods_{{BEHAVIOR_EVENT}}` | Behavior event taxonomy is industry-specific |
| `dws_learning_metric_min` | `dws_{{BEHAVIOR_METRIC}}_min` | Aggregation metrics vary by industry |
| `dws_trade_metric_min.course_id` | `dws_trade_metric_min.product_id` | Trade is always by product, not course |

The `risk_event` and `billing_diff` DWS tables were already industry-agnostic (fraud + payment reconciliation are universal concerns).

## Instantiation Steps

### 1. Copy the templates

```bash
cp ods-template.sql ../iceberg-ddl/retail-ods-tables.sql
cp dws-template.sql ../iceberg-ddl/retail-dws-tables.sql
```

### 2. Replace placeholders

| Placeholder | Replace with | Example (retail) |
|-------------|-------------|-------------------|
| `{{BEHAVIOR_EVENT}}` | Your event stream table suffix | `browse_event` |
| `{{BEHAVIOR_METRIC}}` | Your behavior metric table prefix | `browsing_metric` |
| `{{BEHAVIOR_COLUMNS}}` | Your industry's metric columns | `view_cnt, cart_cnt, purchase_cnt, review_cnt` |

Use `sed` or your editor:

```bash
sed -i 's/{{BEHAVIOR_EVENT}}/browse_event/g' retail-ods-tables.sql
sed -i 's/{{BEHAVIOR_METRIC}}/browsing_metric/g' retail-dws-tables.sql
```

### 3. Add industry-specific columns

Add columns to the behavior event table and behavior metric table as needed. For example, retail might add `cart_value`, `discount_amount`; healthcare might add `diagnosis_code`, `procedure_code`.

### 4. Execute against Iceberg catalog

```sql
-- Via Trino CLI
trino> CREATE DATABASE IF NOT EXISTS ods;
trino> -- run the instantiated DDL file
```

## Example: Education Instantiation

The original education DDL files (`ods-tables.sql`, `dws-tables.sql`) are the education instantiation of these templates:

| Placeholder | Education value |
|-------------|----------------|
| `{{BEHAVIOR_EVENT}}` | `learning_event` |
| `{{BEHAVIOR_METRIC}}` | `learning_metric` |
| `{{BEHAVIOR_COLUMNS}}` | `play_cnt, complete_cnt, submit_cnt, quiz_cnt` |

## Example: Retail Instantiation

| Placeholder | Retail value |
|-------------|-------------|
| `{{BEHAVIOR_EVENT}}` | `browse_event` |
| `{{BEHAVIOR_METRIC}}` | `browsing_metric` |
| `{{BEHAVIOR_COLUMNS}}` | `view_cnt, cart_cnt, purchase_cnt, review_cnt` |

## Backward Compatibility

The original education DDL files are unchanged — they continue to use `course_id` column names for backward compatibility with existing Iceberg tables. The templates are additive: new industries use the templates, existing education tables remain untouched.
