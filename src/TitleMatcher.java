package eu.kanade.tachiyomi.animeextension.pt.tomatobr;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative identity check; similarity is not evidence of the same anime. */
public final class TitleMatcher {
    private static final Pattern SEASON_SUFFIX = Pattern.compile(
        "^(.+?)\\s+(?:(?:season|temporada)\\s+([0-9]+|i{1,3}|iv|v|vi{0,3}|ix|x)|([0-9]+)(?:st|nd|rd|th|a|o)?\\s+(?:season|temporada))$");
    private TitleMatcher() {}

    public static String normalize(String title) {
        if (title == null) return "";
        String text = Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        // Apostrophes do not split possessives; other punctuation separates words.
        text = text.replaceAll("['\u2018\u2019\u02bc]", "")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return text;
    }

    public static boolean matches(String query, String title) {
        String expected = normalize(query);
        return !expected.isEmpty() && expected.equals(normalize(title));
    }

    public static final class RequestedTitle {
        public final String base;
        public final int season;
        private RequestedTitle(String base, int season) { this.base = base; this.season = season; }
    }

    public static RequestedTitle requestedTitle(String title) {
        String normalized = normalize(title);
        Matcher match = SEASON_SUFFIX.matcher(normalized);
        if (!match.matches()) return new RequestedTitle(normalized, 0);
        String number = match.group(2) == null ? match.group(3) : match.group(2);
        int season = 0;
        String[] romans = {"i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};
        for (int i = 0; i < romans.length; i++) if (romans[i].equals(number)) season = i + 1;
        if (season == 0) {
            try { season = Integer.parseInt(number); } catch (NumberFormatException ignored) {}
        }
        if (season < 1 || season > 999) return new RequestedTitle(normalized, 0);
        return new RequestedTitle(match.group(1), season);
    }

    /** Candidate only. Caller must verify the requested season in the API metadata. */
    public static boolean sameSeries(String query, String title) {
        RequestedTitle expected = requestedTitle(query);
        if (expected.season == 0) return matches(query, title);
        RequestedTitle candidate = requestedTitle(title);
        return !expected.base.isEmpty() && expected.base.equals(candidate.base)
            && (candidate.season == 0 || candidate.season == expected.season);
    }

    /** A catalog group number alone may count movies/arcs, rather than TV seasons. */
    public static int seasonLabel(String groupName) {
        String name = normalize(groupName).replaceFirst("^(?:legendado|dublado|subbed|dubbed)\\s+", "");
        if (name.matches(".*\\b(?:filme|film|movie|ova|especial|special)\\b.*")) return 0;
        return requestedTitle("series " + name).season;
    }
}
