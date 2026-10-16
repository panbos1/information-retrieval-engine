package ir.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public class TextProcessor {
    public enum StemmerMode {
        SIMPLE,
        MITOS,
        NONE
    }

    private final Set<String> stopwords = new HashSet<>();
    private StemmerMode stemmerMode = StemmerMode.SIMPLE;
    private boolean mitosAvailable = false;
    private static final int MAX_TOKEN_LENGTH = 64;

    public void setStemmerMode(StemmerMode stemmerMode) {
        this.stemmerMode = stemmerMode;
        if (stemmerMode == StemmerMode.MITOS) {
            this.mitosAvailable = initializeMitosStemmer();
        }
    }

    public void loadStopwords(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String cleaned = normalize(line);
            if (!cleaned.isEmpty()) {
                stopwords.add(cleaned);
            }
        }
    }

    public List<String> process(String text) {
        List<String> result = new ArrayList<>();
        forEachProcessedToken(text, result::add);
        return result;
    }

    public void forEachProcessedToken(String text, Consumer<String> consumer) {
        if (text == null || text.isBlank()) {
            return;
        }

        String normalized = normalize(text);
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (token.length() <= MAX_TOKEN_LENGTH) {
                    token.append(c);
                }
            } else {
                emitToken(token, consumer);
            }
        }
        emitToken(token, consumer);
    }

    private void emitToken(StringBuilder token, Consumer<String> consumer) {
        if (token.length() == 0) {
            return;
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            token.setLength(0);
            return;
        }
        String t = token.toString();
        token.setLength(0);
        if (stopwords.contains(t)) {
            return;
        }
        String stemmed = applyStem(t);
        if (!stemmed.isBlank()) {
            consumer.accept(stemmed);
        }
    }

    public String normalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .trim();
    }

    private String applyStem(String token) {
        if (stemmerMode == StemmerMode.NONE) {
            return token;
        }
        if (stemmerMode == StemmerMode.MITOS) {
            String stemmed = stemWithMitos(token);
            if (stemmed != null && !stemmed.isBlank()) {
                return stemmed;
            }
            return simpleStem(token);
        }
        return simpleStem(token);
    }

    private boolean initializeMitosStemmer() {
        try {
            Class<?> stemmerClass = Class.forName("mitos.stemmer.Stemmer");
            stemmerClass.getMethod("Initialize").invoke(null);
            return true;
        } catch (Exception ex) {
            System.err.println("MITOS stemmer not available, fallback to simple stemming.");
            return false;
        }
    }

    private String stemWithMitos(String token) {
        if (!mitosAvailable) {
            return null;
        }
        try {
            Class<?> stemmerClass = Class.forName("mitos.stemmer.Stemmer");
            Object value = stemmerClass.getMethod("Stem", String.class).invoke(null, token);
            return value == null ? null : value.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private String simpleStem(String token) {
        if (token.length() <= 3) {
            return token;
        }
        if (token.endsWith("ing") && token.length() > 5) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ed") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("es") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 4) {
            return token.substring(0, token.length() - 1);
        }
        if ((token.endsWith("ος") || token.endsWith("ης") || token.endsWith("ες")) && token.length() > 5) {
            return token.substring(0, token.length() - 2);
        }
        if ((token.endsWith("ων") || token.endsWith("ει") || token.endsWith("εις")) && token.length() > 5) {
            return token.substring(0, token.length() - 2);
        }
        return token;
    }
}
