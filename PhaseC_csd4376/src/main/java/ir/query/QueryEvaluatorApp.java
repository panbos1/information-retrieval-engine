package ir.query;

import ir.common.TextProcessor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class QueryEvaluatorApp {
    public static void main(String[] args) throws Exception {
        Map<String, String> argMap = parseArgs(args);
        Path indexDir = getRequired(argMap, "--index");
        Path stopEn = argMap.containsKey("--stop-en") ? Path.of(argMap.get("--stop-en")) : null;
        Path stopGr = argMap.containsKey("--stop-gr") ? Path.of(argMap.get("--stop-gr")) : null;
        int topK = Integer.parseInt(argMap.getOrDefault("--topK", "20"));
        boolean useMitosStemmer = Boolean.parseBoolean(argMap.getOrDefault("--use-mitos-stemmer", "false"));
        boolean clinicalMode = Boolean.parseBoolean(argMap.getOrDefault("--clinical-mode", "false"));
        boolean useSummary = Boolean.parseBoolean(argMap.getOrDefault("--use-summary", "true"));

        TextProcessor processor = new TextProcessor();
        processor.setStemmerMode(useMitosStemmer ? TextProcessor.StemmerMode.MITOS : TextProcessor.StemmerMode.SIMPLE);
        processor.loadStopwords(stopEn);
        processor.loadStopwords(stopGr);

        QueryEngine engine = new QueryEngine(indexDir, processor);
        Scanner scanner = new Scanner(System.in);
        if (!clinicalMode) {
            System.out.println("Type your query (or 'exit').");
        } else {
            System.out.println("Clinical mode enabled. Type topic data (or 'exit').");
        }
        while (true) {
            String q;
            if (!clinicalMode) {
                System.out.print("query> ");
                q = scanner.nextLine();
            } else {
                System.out.print("topic type [diagnosis|test|treatment]> ");
                String type = scanner.nextLine().trim();
                if ("exit".equalsIgnoreCase(type)) {
                    break;
                }
                if (useSummary) {
                    System.out.print("topic summary> ");
                } else {
                    System.out.print("topic description> ");
                }
                String content = scanner.nextLine();
                q = type + " " + type + " " + content;
            }

            if ("exit".equalsIgnoreCase(q.trim())) {
                break;
            }
            var results = engine.search(q, topK);
            if (results.isEmpty()) {
                System.out.println("No results.");
                continue;
            }
            int rank = 1;
            for (var r : results) {
                System.out.printf("%d\t%s\t%s\t%.6f%n", rank++, r.document().pmcid(), r.document().path(), r.score());
            }
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
}
