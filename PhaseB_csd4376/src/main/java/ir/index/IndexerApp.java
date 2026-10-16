package ir.index;

import ir.common.TextProcessor;

import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public class IndexerApp {
    private static final double TITLE_W = 3.0;
    private static final double ABSTRACT_W = 2.0;
    private static final double BODY_W = 1.0;
    private static final double AUTHORS_W = 1.5;
    private static final double CATEGORIES_W = 2.0;
    private static final double JOURNAL_W = 1.2;
    private static final double PUBLISHER_W = 1.0;
    private static final int MAX_TITLE_CHARS = 20_000;
    private static final int MAX_ABSTRACT_CHARS = 80_000;
    private static final int MAX_BODY_CHARS = 400_000;
    private static final int MAX_MISC_CHARS = 40_000;

    public static void main(String[] args) throws Exception {
        Map<String, String> argMap = parseArgs(args);
        Path inputDir = getRequired(argMap, "--input");
        Path outputDir = getRequired(argMap, "--output");
        Path stopEn = argMap.containsKey("--stop-en") ? Path.of(argMap.get("--stop-en")) : null;
        Path stopGr = argMap.containsKey("--stop-gr") ? Path.of(argMap.get("--stop-gr")) : null;
        boolean useMitosStemmer = Boolean.parseBoolean(argMap.getOrDefault("--use-mitos-stemmer", "false"));
        boolean useBioReader = Boolean.parseBoolean(argMap.getOrDefault("--use-bioreader", "false"));
        boolean storePositions = Boolean.parseBoolean(argMap.getOrDefault("--store-positions", "true"));

        Files.createDirectories(outputDir);
        TextProcessor processor = new TextProcessor();
        processor.setStemmerMode(useMitosStemmer ? TextProcessor.StemmerMode.MITOS : TextProcessor.StemmerMode.SIMPLE);
        processor.loadStopwords(stopEn);
        processor.loadStopwords(stopGr);

        Map<String, Map<Integer, PostingAccumulator>> inverted = new TreeMap<>();
        Map<Integer, DocumentInfo> documents = new LinkedHashMap<>();
        Map<Integer, Map<String, Double>> docTermWeights = new HashMap<>();
        int skippedFiles = 0;

        int docId = 0;
        try (Stream<Path> paths = Files.walk(inputDir)) {
            List<Path> files = paths
                    .filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".nxml"))
                    .sorted(Comparator.naturalOrder())
                    .toList();

            for (Path file : files) {
                try {
                    docId++;
                    NxmlArticle article = readArticle(file, useBioReader);
                    int positionCounter = 0;

                    Map<String, Double> currentDocWeights = new HashMap<>();
                    positionCounter = indexField(article.title(), TITLE_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);
                    positionCounter = indexField(article.abstr(), ABSTRACT_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);
                    positionCounter = indexField(article.body(), BODY_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);
                    positionCounter = indexField(article.authors(), AUTHORS_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);
                    positionCounter = indexField(article.categories(), CATEGORIES_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);
                    positionCounter = indexField(article.journal(), JOURNAL_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);
                    indexField(article.publisher(), PUBLISHER_W, docId, positionCounter, processor, inverted, currentDocWeights, storePositions);

                    documents.put(docId, new DocumentInfo(docId, file.toAbsolutePath().toString(), article.pmcid()));
                    docTermWeights.put(docId, currentDocWeights);
                } catch (Exception ex) {
                    skippedFiles++;
                    docId--;
                    System.err.printf("Skipping invalid file %s (%s)%n", file, ex.getClass().getSimpleName());
                }
            }
        }

        int totalDocs = documents.size();
        Map<Integer, Double> norms = computeNorms(inverted, docTermWeights, totalDocs);
        writeIndex(outputDir, inverted, documents, norms, storePositions);

        System.out.printf("Indexed %d documents into %s (skipped %d)%n", totalDocs, outputDir.toAbsolutePath(), skippedFiles);
    }

    private static int indexField(
            String text,
            double weight,
            int docId,
            int positionCounter,
            TextProcessor processor,
            Map<String, Map<Integer, PostingAccumulator>> inverted,
            Map<String, Double> currentDocWeights,
            boolean storePositions
    ) {
        final int[] pos = {positionCounter};
        processor.forEachProcessedToken(text, token -> {
            pos[0]++;
            Map<Integer, PostingAccumulator> postings = inverted.computeIfAbsent(token, ignored -> new HashMap<>());
            PostingAccumulator accumulator = postings.computeIfAbsent(docId, ignored -> new PostingAccumulator());
            if (storePositions) {
                accumulator.positions.add(pos[0]);
            }
            accumulator.tf += weight;
            currentDocWeights.merge(token, weight, Double::sum);
        });
        return pos[0];
    }

    private static Map<Integer, Double> computeNorms(
            Map<String, Map<Integer, PostingAccumulator>> inverted,
            Map<Integer, Map<String, Double>> docTermWeights,
            int totalDocs
    ) {
        Map<Integer, Double> sums = new HashMap<>();
        for (Map.Entry<String, Map<Integer, PostingAccumulator>> termEntry : inverted.entrySet()) {
            int df = termEntry.getValue().size();
            double idf = Math.log((totalDocs + 1.0) / (df + 1.0)) + 1.0;
            for (Map.Entry<Integer, PostingAccumulator> postingEntry : termEntry.getValue().entrySet()) {
                int docId = postingEntry.getKey();
                double tf = postingEntry.getValue().tf;
                double weight = tf * idf;
                sums.merge(docId, weight * weight, Double::sum);
            }
        }
        Map<Integer, Double> norms = new HashMap<>();
        for (Map.Entry<Integer, Double> e : sums.entrySet()) {
            norms.put(e.getKey(), Math.sqrt(e.getValue()));
        }
        for (Integer docId : docTermWeights.keySet()) {
            norms.putIfAbsent(docId, 1.0);
        }
        return norms;
    }

    private static void writeIndex(
            Path outputDir,
            Map<String, Map<Integer, PostingAccumulator>> inverted,
            Map<Integer, DocumentInfo> documents,
            Map<Integer, Double> norms,
            boolean storePositions
    ) throws IOException {
        Path vocabularyPath = outputDir.resolve("VocabularyFile.tsv");
        Path docsPath = outputDir.resolve("DocumentsFile.tsv");
        Path postingPath = outputDir.resolve("PostingFile.tsv");

        try (RandomAccessFile postingRaf = new RandomAccessFile(postingPath.toFile(), "rw")) {
            postingRaf.setLength(0);
            List<String> vocabularyLines = new ArrayList<>();

            for (Map.Entry<String, Map<Integer, PostingAccumulator>> entry : inverted.entrySet()) {
                String term = entry.getKey();
                long pointer = postingRaf.getFilePointer();
                int df = entry.getValue().size();

                List<Integer> docIds = new ArrayList<>(entry.getValue().keySet());
                docIds.sort(Comparator.naturalOrder());
                for (Integer docId : docIds) {
                    PostingAccumulator p = entry.getValue().get(docId);
                    String positionsCsv = "";
                    if (storePositions) {
                        positionsCsv = p.positions.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                    }
                    String line = term + "\t" + docId + "\t" + p.tf + "\t" + positionsCsv + System.lineSeparator();
                    postingRaf.write(line.getBytes(StandardCharsets.UTF_8));
                }

                vocabularyLines.add(term + "\t" + df + "\t" + pointer);
            }
            Files.write(vocabularyPath, vocabularyLines, StandardCharsets.UTF_8);
        }

        List<String> docLines = new ArrayList<>();
        for (DocumentInfo doc : documents.values()) {
            double norm = norms.getOrDefault(doc.docId, 1.0);
            docLines.add(doc.docId + "\t" + doc.path + "\t" + doc.pmcid + "\t" + norm);
        }
        Files.write(docsPath, docLines, StandardCharsets.UTF_8);
    }

    private static NxmlArticle readArticle(Path file, boolean useBioReader) throws Exception {
        if (useBioReader) {
            NxmlArticle fromBioReader = readArticleWithBioReader(file);
            if (fromBioReader != null) {
                return fromBioReader;
            }
            System.err.println("BioReader not available, fallback to built-in XML parser.");
        }

        String pmcid = file.getFileName().toString().replace(".nxml", "");
        ParsedArticleFields fields = parseArticleWithSax(file);
        String title = fields.title;
        String abstr = fields.abstr;
        String body = fields.body;
        String journal = fields.journal;
        String publisher = fields.publisher;
        String authors = fields.authors;
        String categories = fields.categories;
        return new NxmlArticle(pmcid, title, abstr, body, journal, publisher, authors, categories);
    }

    private static ParsedArticleFields parseArticleWithSax(Path file) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // Keep parser usable even if a feature is unsupported.
        }

        var parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        reader.setEntityResolver(emptyEntityResolver());

        StringBuilder title = new StringBuilder();
        StringBuilder abstr = new StringBuilder();
        StringBuilder body = new StringBuilder();
        StringBuilder journal = new StringBuilder();
        StringBuilder publisher = new StringBuilder();
        StringBuilder authors = new StringBuilder();
        StringBuilder categories = new StringBuilder();

        class Handler extends DefaultHandler {
            int inTitle = 0;
            int inAbstract = 0;
            int inBody = 0;
            int inJournal = 0;
            int inPublisher = 0;
            int inAuthors = 0;
            int inCategories = 0;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                String tag = qName == null ? "" : qName.toLowerCase();
                if ("article-title".equals(tag)) inTitle++;
                if ("abstract".equals(tag)) inAbstract++;
                if ("body".equals(tag)) inBody++;
                if ("journal-title".equals(tag)) inJournal++;
                if ("publisher-name".equals(tag)) inPublisher++;
                if ("contrib-group".equals(tag)) inAuthors++;
                if ("article-categories".equals(tag)) inCategories++;
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                String tag = qName == null ? "" : qName.toLowerCase();
                if ("article-title".equals(tag) && inTitle > 0) inTitle--;
                if ("abstract".equals(tag) && inAbstract > 0) inAbstract--;
                if ("body".equals(tag) && inBody > 0) inBody--;
                if ("journal-title".equals(tag) && inJournal > 0) inJournal--;
                if ("publisher-name".equals(tag) && inPublisher > 0) inPublisher--;
                if ("contrib-group".equals(tag) && inAuthors > 0) inAuthors--;
                if ("article-categories".equals(tag) && inCategories > 0) inCategories--;
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (length <= 0) {
                    return;
                }
                if (inTitle > 0) appendLimited(title, ch, start, length, MAX_TITLE_CHARS);
                if (inAbstract > 0) appendLimited(abstr, ch, start, length, MAX_ABSTRACT_CHARS);
                if (inBody > 0) appendLimited(body, ch, start, length, MAX_BODY_CHARS);
                if (inJournal > 0) appendLimited(journal, ch, start, length, MAX_MISC_CHARS);
                if (inPublisher > 0) appendLimited(publisher, ch, start, length, MAX_MISC_CHARS);
                if (inAuthors > 0) appendLimited(authors, ch, start, length, MAX_MISC_CHARS);
                if (inCategories > 0) appendLimited(categories, ch, start, length, MAX_MISC_CHARS);
            }
        }

        reader.setContentHandler(new Handler());
        try {
            reader.parse(file.toUri().toString());
        } catch (SAXException ex) {
            throw ex;
        }

        return new ParsedArticleFields(
                title.toString(),
                abstr.toString(),
                body.toString(),
                journal.toString(),
                publisher.toString(),
                authors.toString(),
                categories.toString()
        );
    }

    private static NxmlArticle readArticleWithBioReader(Path file) {
        try {
            Class<?> readerClass = Class.forName("gr.uoc.csd.hy463.NXMLFileReader");
            Object reader = readerClass.getConstructor(java.io.File.class).newInstance(file.toFile());
            String pmcid = invokeString(readerClass, reader, "getPMCID");
            String title = invokeString(readerClass, reader, "getTitle");
            String abstr = invokeString(readerClass, reader, "getAbstr");
            String body = invokeString(readerClass, reader, "getBody");
            String journal = invokeString(readerClass, reader, "getJournal");
            String publisher = invokeString(readerClass, reader, "getPublisher");
            String authors = invokeObject(readerClass, reader, "getAuthors");
            String categories = invokeObject(readerClass, reader, "getCategories");
            return new NxmlArticle(
                    pmcid == null || pmcid.isBlank() ? file.getFileName().toString().replace(".nxml", "") : pmcid,
                    nullToEmpty(title),
                    nullToEmpty(abstr),
                    nullToEmpty(body),
                    nullToEmpty(journal),
                    nullToEmpty(publisher),
                    nullToEmpty(authors),
                    nullToEmpty(categories)
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private static String invokeString(Class<?> clazz, Object instance, String method) throws Exception {
        Object value = clazz.getMethod(method).invoke(instance);
        return value == null ? "" : value.toString();
    }

    private static String invokeObject(Class<?> clazz, Object instance, String method) throws Exception {
        Object value = clazz.getMethod(method).invoke(instance);
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return String.join(" ", list.stream().map(String::valueOf).toList());
        }
        return value.toString();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static EntityResolver emptyEntityResolver() {
        return (publicId, systemId) -> new InputSource(new java.io.StringReader(""));
    }

    private static void appendLimited(StringBuilder sb, char[] ch, int start, int length, int maxChars) {
        if (sb.length() >= maxChars) {
            return;
        }
        int remaining = maxChars - sb.length();
        int len = Math.min(length, remaining);
        if (len > 0) {
            sb.append(ch, start, len);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            out.put(args[i], args[i + 1]);
        }
        return out;
    }

    private static Path getRequired(Map<String, String> args, String key) {
        if (!args.containsKey(key)) {
            throw new IllegalArgumentException("Missing required argument " + key);
        }
        return Path.of(args.get(key));
    }

    private static class PostingAccumulator {
        private double tf = 0.0;
        private final List<Integer> positions = new ArrayList<>();
    }

    private record ParsedArticleFields(String title, String abstr, String body, String journal, String publisher, String authors, String categories) {}
    private record DocumentInfo(int docId, String path, String pmcid) {}
    private record NxmlArticle(String pmcid, String title, String abstr, String body, String journal, String publisher, String authors, String categories) {}
}
