package ir.eval;

import ir.common.TextProcessor;
import ir.query.QueryEngine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IRQualityEvaluatorApp {
    public static void main(String[] args) throws Exception {
        Map<String, String> argMap = parseArgs(args);
        Path indexDir = getRequired(argMap, "--index");
        Path topicsPath = getRequired(argMap, "--topics");
        Path qrelsPath = getRequired(argMap, "--qrels");
        Path resultsPath = Path.of(argMap.getOrDefault("--results", "results.txt"));
        Path evalPath = Path.of(argMap.getOrDefault("--eval", "eval_results.txt"));
        String runName = argMap.getOrDefault("--run-name", "runA");
        boolean useSummary = Boolean.parseBoolean(argMap.getOrDefault("--use-summary", "true"));
        boolean includeTopicType = Boolean.parseBoolean(argMap.getOrDefault("--include-topic-type", "true"));
        boolean useMitosStemmer = Boolean.parseBoolean(argMap.getOrDefault("--use-mitos-stemmer", "false"));
        boolean useBioReaderTopics = Boolean.parseBoolean(argMap.getOrDefault("--use-bioreader-topics", "false"));

        TextProcessor processor = new TextProcessor();
        processor.setStemmerMode(useMitosStemmer ? TextProcessor.StemmerMode.MITOS : TextProcessor.StemmerMode.SIMPLE);
        if (argMap.containsKey("--stop-en")) {
            processor.loadStopwords(Path.of(argMap.get("--stop-en")));
        }
        if (argMap.containsKey("--stop-gr")) {
            processor.loadStopwords(Path.of(argMap.get("--stop-gr")));
        }

        QueryEngine engine = new QueryEngine(indexDir, processor);
        List<Topic> topics = readTopics(topicsPath, useBioReaderTopics);
        Map<Integer, Map<String, Integer>> qrels = readQrels(qrelsPath);

        List<String> resultLines = new ArrayList<>();
        List<String> evalLines = new ArrayList<>();

        for (Topic topic : topics) {
            String text = useSummary ? topic.summary : topic.description;
            if (includeTopicType && topic.type != null && !topic.type.isBlank()) {
                text = topic.type + " " + topic.type + " " + text;
            }
            var results = engine.search(text, 1000);
            int rank = 1;
            for (var r : results) {
                resultLines.add(topic.number + "\t0\t" + r.document().pmcid() + "\t" + rank + "\t" + r.score() + "\t" + runName);
                rank++;
            }

            Map<String, Integer> topicQrels = qrels.getOrDefault(topic.number, Map.of());
            double bpref = bpref(results, topicQrels);
            double avep = avepPrime(results, topicQrels);
            double ndcg = ndcgPrime(results, topicQrels);
            evalLines.add(topic.number + "\t" + bpref + "\t" + avep + "\t" + ndcg);
        }

        Files.write(resultsPath, resultLines, StandardCharsets.UTF_8);
        Files.write(evalPath, evalLines, StandardCharsets.UTF_8);
        System.out.println("Wrote " + resultsPath.toAbsolutePath());
        System.out.println("Wrote " + evalPath.toAbsolutePath());
    }

    private static double bpref(List<QueryEngine.SearchResult> retrieved, Map<String, Integer> qrels) {
        List<String> relevant = qrels.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
        if (relevant.isEmpty()) {
            return 0.0;
        }
        int r = relevant.size();
        Set<String> relevantSet = new HashSet<>(relevant);
        Set<String> nonRelevantSet = new HashSet<>();
        for (Map.Entry<String, Integer> e : qrels.entrySet()) {
            if (e.getValue() == 0) {
                nonRelevantSet.add(e.getKey());
            }
        }

        int seenNonRel = 0;
        double sum = 0.0;
        for (QueryEngine.SearchResult result : retrieved) {
            String pmcid = result.document().pmcid();
            if (!qrels.containsKey(pmcid)) {
                continue;
            }
            if (nonRelevantSet.contains(pmcid)) {
                seenNonRel++;
            } else if (relevantSet.contains(pmcid)) {
                sum += 1.0 - (Math.min(seenNonRel, r) / (double) r);
            }
        }
        return sum / r;
    }

    private static double avepPrime(List<QueryEngine.SearchResult> retrieved, Map<String, Integer> qrels) {
        List<QueryEngine.SearchResult> judged = retrieved.stream()
                .filter(r -> qrels.containsKey(r.document().pmcid()))
                .toList();

        long totalRelevant = qrels.values().stream().filter(v -> v > 0).count();
        if (totalRelevant == 0) {
            return 0.0;
        }

        int retrievedRelevant = 0;
        double sumPrecisions = 0.0;
        for (int i = 0; i < judged.size(); i++) {
            String pmcid = judged.get(i).document().pmcid();
            if (qrels.getOrDefault(pmcid, 0) > 0) {
                retrievedRelevant++;
                sumPrecisions += retrievedRelevant / (double) (i + 1);
            }
        }
        return sumPrecisions / totalRelevant;
    }

    private static double ndcgPrime(List<QueryEngine.SearchResult> retrieved, Map<String, Integer> qrels) {
        List<Integer> rels = retrieved.stream()
                .filter(r -> qrels.containsKey(r.document().pmcid()))
                .map(r -> qrels.getOrDefault(r.document().pmcid(), 0))
                .toList();

        if (rels.isEmpty()) {
            return 0.0;
        }

        double dcg = 0.0;
        for (int i = 0; i < rels.size(); i++) {
            int rel = rels.get(i);
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        List<Integer> ideal = qrels.values().stream().sorted(Comparator.reverseOrder()).toList();
        double idcg = 0.0;
        for (int i = 0; i < Math.min(ideal.size(), rels.size()); i++) {
            int rel = ideal.get(i);
            idcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static List<Topic> readTopics(Path topicsPath, boolean useBioReaderTopics) throws Exception {
        if (useBioReaderTopics) {
            List<Topic> fromBioReader = readTopicsWithBioReader(topicsPath);
            if (!fromBioReader.isEmpty()) {
                return fromBioReader;
            }
            System.err.println("BioReader TopicsReader not available, fallback to built-in XML parser.");
        }

        var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var document = builder.parse(topicsPath.toFile());
        document.getDocumentElement().normalize();
        var topicNodes = document.getElementsByTagName("topic");

        List<Topic> topics = new ArrayList<>();
        for (int i = 0; i < topicNodes.getLength(); i++) {
            var node = topicNodes.item(i);
            var children = node.getChildNodes();
            int number = -1;
            String type = "";
            String summary = "";
            String description = "";

            var attrs = node.getAttributes();
            if (attrs != null) {
                var numberAttr = attrs.getNamedItem("number");
                if (numberAttr != null) {
                    number = Integer.parseInt(numberAttr.getNodeValue().trim());
                }
                var typeAttr = attrs.getNamedItem("type");
                if (typeAttr != null) {
                    type = typeAttr.getNodeValue().trim();
                }
            }

            for (int c = 0; c < children.getLength(); c++) {
                var child = children.item(c);
                String name = child.getNodeName();
                String value = child.getTextContent().trim();
                if ("number".equalsIgnoreCase(name)) {
                    number = Integer.parseInt(value);
                } else if ("type".equalsIgnoreCase(name)) {
                    type = value;
                } else if ("summary".equalsIgnoreCase(name)) {
                    summary = value;
                } else if ("description".equalsIgnoreCase(name)) {
                    description = value;
                }
            }
            if (number != -1) {
                topics.add(new Topic(number, type, summary, description));
            }
        }
        return topics;
    }

    private static List<Topic> readTopicsWithBioReader(Path topicsPath) {
        try {
            Class<?> readerClass = Class.forName("gr.uoc.csd.hy463.TopicsReader");
            Class<?> topicClass = Class.forName("gr.uoc.csd.hy463.Topic");
            @SuppressWarnings("unchecked")
            List<Object> rawTopics = (List<Object>) readerClass.getMethod("readTopics", String.class)
                    .invoke(null, topicsPath.toString());

            List<Topic> topics = new ArrayList<>();
            for (Object raw : rawTopics) {
                int number = Integer.parseInt(String.valueOf(topicClass.getMethod("getNumber").invoke(raw)));
                String type = String.valueOf(topicClass.getMethod("getType").invoke(raw));
                String summary = String.valueOf(topicClass.getMethod("getSummary").invoke(raw));
                String description = String.valueOf(topicClass.getMethod("getDescription").invoke(raw));
                topics.add(new Topic(number, type, summary, description));
            }
            return topics;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Map<Integer, Map<String, Integer>> readQrels(Path qrelsPath) throws Exception {
        Map<Integer, Map<String, Integer>> qrels = new HashMap<>();
        for (String line : Files.readAllLines(qrelsPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.trim().split("\\s+");
            if (p.length < 4) {
                continue;
            }
            int topicNo = Integer.parseInt(p[0]);
            String pmcid = p[2];
            int rel = Integer.parseInt(p[3]);
            qrels.computeIfAbsent(topicNo, ignored -> new HashMap<>()).put(pmcid, rel);
        }
        return qrels;
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

    private record Topic(int number, String type, String summary, String description) {}
}
