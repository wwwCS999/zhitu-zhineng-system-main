package com.zhitu.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

public final class TextUtils {
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private TextUtils() {}

    public static String normalize(String text) {
        if (text == null) return "";
        return SPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static double jaccard(String a, String b) {
        Set<String> x = tokens(a), y = tokens(b);
        if (x.isEmpty() && y.isEmpty()) return 1;
        Set<String> intersection = new HashSet<>(x);
        intersection.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    public static Set<String> tokens(String s) {
        String n = normalize(s).replaceAll("[^\\p{L}\\p{N}+#.]", " ");
        Set<String> result = new LinkedHashSet<>();
        for (String token : n.split(" ")) if (token.length() > 1) result.add(token);
        return result;
    }

    public static List<String> jsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        String s = json.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        List<String> result = new ArrayList<>();
        for (String item : s.split(",")) {
            String value = item.trim().replaceAll("^[\"]|[\"]$", "");
            if (!value.isBlank()) result.add(value.replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return result;
    }

    public static String jsonArray(Collection<String> items) {
        return items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .distinct()
                .map(x -> "\"" + x.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
