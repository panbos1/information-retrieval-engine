package ir.query;

import ir.common.IndexIO;
import ir.common.TextProcessor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryEngine {
    public record SearchResult(IndexIO.DocumentEntry document, double score) {}

    private final TextProcessor processor;
    private final Map<String, IndexIO.VocabularyEntry> vocabulary;
    private final Map<Integer, IndexIO.DocumentEntry> documents;
    private final Path postingPath;
    private final int totalDocs;

    public QueryEngine(Path indexDir, TextProcessor processor) throws IOException {
        this.processor = processor;
        this.vocabulary = IndexIO.loadVocabulary(indexDir.resolve("VocabularyFile.tsv"));
        this.documents = IndexIO.loadDocuments(indexDir.resolve("DocumentsFile.tsv"));
        this.postingPath = indexDir.resolve("PostingFile.tsv");
        this.totalDocs = documents.size();
    }

    public List<SearchResult> search(String query, int topK) throws IOException {
        List<String> terms = processor.process(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> queryTf = new HashMap<>();
        for (String term : terms) {
            queryTf.merge(term, 1, Integer::sum);
        }

        Map<String, Double> queryWeights = new HashMap<>();
        double queryNormSq = 0.0;
        for (Map.Entry<String, Integer> e : queryTf.entrySet()) {
            IndexIO.VocabularyEntry v = vocabulary.get(e.getKey());
            if (v == null) {
                continue;
            }
            double idf = Math.log((totalDocs + 1.0) / (v.df() + 1.0)) + 1.0;
            double w = e.getValue() * idf;
            queryWeights.put(e.getKey(), w);
            queryNormSq += w * w;
        }
        double queryNorm = Math.sqrt(queryNormSq == 0 ? 1.0 : queryNormSq);

        Map<Integer, Double> scores = new HashMap<>();
        for (Map.Entry<String, Double> termWeight : queryWeights.entrySet()) {
            IndexIO.VocabularyEntry ve = vocabulary.get(termWeight.getKey());
            double idf = Math.log((totalDocs + 1.0) / (ve.df() + 1.0)) + 1.0;
            for (IndexIO.Posting posting : IndexIO.loadPostingsForTerm(postingPath, ve)) {
                double docWeight = posting.tf() * idf;
                scores.merge(posting.docId(), termWeight.getValue() * docWeight, Double::sum);
            }
        }

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            IndexIO.DocumentEntry doc = documents.get(e.getKey());
            if (doc == null) {
                continue;
            }
            double normScore = e.getValue() / (doc.norm() * queryNorm + 1e-9);
            results.add(new SearchResult(doc, normScore));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results.size() > topK ? results.subList(0, topK) : results;
    }
}
