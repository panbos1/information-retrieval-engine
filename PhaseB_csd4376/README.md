# HY463 Project - Phase B
csd4376
Panagiotis Bermperidis

This folder contains only the required material for Phase B submission.

## Included
- `src/` (query/evaluation code: `ir.common`, `ir.query`, `ir.eval`)
- `dist/queryevaluator.jar`
- `dist/irqualityevaluator.jar`
- `pom.xml`
- `doc/report_placeholder.txt`

## Run Query Evaluator

```powershell
java -jar dist/queryevaluator.jar --index <path_to_index_dir> --stop-en <path_to_stopwordsEn.txt> --stop-gr <path_to_stopwordsGr.txt> --topK 20
```

## Run Quality Evaluator

```powershell
java -jar dist/irqualityevaluator.jar --index <path_to_index_dir> --topics <path_to_topics.xml> --qrels <path_to_qrels.txt> --results <results.txt> --eval <eval_results.txt> --run-name runA --use-summary true --include-topic-type true --stop-en <path_to_stopwordsEn.txt> --stop-gr <path_to_stopwordsGr.txt>
```
