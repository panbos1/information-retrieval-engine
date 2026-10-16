package ir.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IndexIO {
    private IndexIO() {}

    public record VocabularyEntry(String term, int df, long pointer) {}
    public record DocumentEntry(int id, String path, String pmcid, double norm) {}
    public record Posting(int docId, double tf, List<Integer> positions) {}

    public static Map<String, VocabularyEntry> loadVocabulary(Path vocabularyPath) throws IOException {
        Map<String, VocabularyEntry> vocabulary = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(vocabularyPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 3) {
                    continue;
                }
                String term = parts[0];
                int df = Integer.parseInt(parts[1]);
                long pointer = Long.parseLong(parts[2]);
                vocabulary.put(term, new VocabularyEntry(term, df, pointer));
            }
        }
        return vocabulary;
    }

    public static Map<Integer, DocumentEntry> loadDocuments(Path docsPath) throws IOException {
        Map<Integer, DocumentEntry> documents = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(docsPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 4) {
                    continue;
                }
                int docId = Integer.parseInt(parts[0]);
                String path = parts[1];
                String pmcid = parts[2];
                double norm = Double.parseDouble(parts[3]);
                documents.put(docId, new DocumentEntry(docId, path, pmcid, norm));
            }
        }
        return documents;
    }

    public static List<Posting> loadPostingsForTerm(Path postingPath, VocabularyEntry entry) throws IOException {
        List<Posting> postings = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(postingPath.toFile(), "r")) {
            raf.seek(entry.pointer());
            String line;
            while ((line = raf.readLine()) != null) {
                String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\t");
                if (parts.length < 4) {
                    break;
                }
                String term = parts[0];
                if (!term.equals(entry.term())) {
                    break;
                }
                int docId = Integer.parseInt(parts[1]);
                double tf = Double.parseDouble(parts[2]);
                List<Integer> positions = parsePositions(parts[3]);
                postings.add(new Posting(docId, tf, positions));
            }
        }
        return postings;
    }

    private static List<Integer> parsePositions(String csv) {
        List<Integer> positions = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return positions;
        }
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                positions.add(Integer.parseInt(part));
            }
        }
        return positions;
    }
}
