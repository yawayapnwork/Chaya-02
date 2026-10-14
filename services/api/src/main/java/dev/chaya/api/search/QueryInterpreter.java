package dev.chaya.api.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Turns a typed or spoken query ("Find the nearest staircase", "where's the recepton?", "wheelchair-accessible entrance")
 * into what SemanticSearchService searches for. Rules only, deterministic, no model: the same text always gives the same
 * interpretation (docs/search.md, "Query understanding").
 *
 * <ol>
 *   <li><b>Normalise:</b> Unicode NFKC, lower case, curly quotes and dashes folded, punctuation to spaces, whitespace
 *       collapsed.</li>
 *   <li><b>Modifiers</b> are recognised and removed from the text that is matched: a <i>spatial</i> one ("nearest",
 *       "closest", "nearby", "near me") and an <i>accessibility</i> one ("wheelchair accessible", "step-free",
 *       "barrier-free", "accessible"). Neither is ever answered from the text: nearest needs the caller's position, and
 *       accessibility is only ever what staff recorded on a POI ({@code attributes.accessible}), never inferred from a label
 *       or from what a detection looks like.</li>
 *   <li><b>Request phrasing</b> at the start ("where is", "find", "show me", "take me to", articles) and courtesy at the end
 *       ("please", "here") is stripped. What is left is the subject, e.g. "staircase".</li>
 *   <li><b>Spelling:</b> a word of the subject that is in neither the venue's own vocabulary (the words of its POI labels,
 *       categories and tags) nor the synonym table below is corrected to the one word of those that is closest by
 *       Damerau-Levenshtein distance: at most 1 edit for words of 5-7 letters, 2 for longer ones, and only when exactly one
 *       candidate is that close. Shorter words are never corrected ("gift" stays "gift", it does not become "lift"), and
 *       neither is the singular or plural of a dictionary word ("armchair" next to the venue's "armchairs").</li>
 *   <li><b>Synonyms:</b> a small table of common venue words (stairs/staircase/stairway, elevator/lift, restroom/toilet/loo,
 *       sofa/couch/settee, ...). It does not replace CLIP: the subject is still embedded as typed (after spelling), and the
 *       table only adds alternative phrasings to the lexical match, so "loo" matches a POI tagged "toilet" by name. It holds
 *       synonyms and plurals only, not kinds of a thing: "armchair" is not a synonym of "chair".</li>
 * </ol>
 *
 * <p>What it does not do: interpret free sentences beyond those request phrases, translate, or guess an intent. A query it
 * does not recognise is searched as typed.
 */
public final class QueryInterpreter {

    /** Synonym groups. The first entry of each is the group's name. Multi-word entries are matched as whole phrases. */
    static final List<List<String>> SYNONYMS = List.of(
        List.of("stairs", "staircase", "stairway", "stairwell", "steps", "stair"),
        List.of("elevator", "lift", "elevators", "lifts"),
        List.of("escalator", "escalators", "moving stairs"),
        List.of("restroom", "toilet", "toilets", "restrooms", "bathroom", "bathrooms", "washroom", "lavatory", "wc", "loo"),
        List.of("entrance", "entry", "way in", "front door", "entrances"),
        List.of("exit", "way out", "exits"),
        List.of("emergency exit", "fire exit", "emergency door"),
        List.of("reception", "reception desk", "front desk", "check-in", "check in"),
        List.of("information desk", "info desk", "help desk", "information point", "information kiosk"),
        List.of("sofa", "couch", "settee", "sofas", "couches"),
        List.of("chair", "chairs"),
        List.of("table", "tables"),
        List.of("bench", "benches"),
        List.of("plant", "plants", "houseplant", "potted plant"),
        List.of("cafe", "coffee shop", "coffee bar", "cafeteria"),
        List.of("atm", "cash machine", "cashpoint", "cash point"),
        List.of("cloakroom", "coat check", "coatroom", "cloak room"),
        List.of("first aid", "first aid station", "first aid kit", "medical station"),
        List.of("water fountain", "drinking fountain", "drinking water"),
        List.of("meeting room", "conference room", "meeting rooms"),
        List.of("fire extinguisher", "extinguisher"),
        List.of("trash can", "bin", "rubbish bin", "garbage can", "waste bin"),
        List.of("parking", "car park", "parking lot"));

    private static final List<String> SPATIAL = List.of("closest", "nearest", "nearby", "near me", "close by", "closest to me",
        "nearest to me", "near here", "around here");

    private static final List<String> ACCESSIBILITY = List.of("wheelchair accessible", "wheelchair friendly", "wheelchair access",
        "accessible for wheelchairs", "accessible by wheelchair", "for wheelchairs", "for wheelchair users", "step free",
        "barrier free", "disabled access", "handicap accessible", "accessible");

    // Longest first, so "where can i find" wins over "where".
    private static final List<String> LEADING = sortedLongestFirst(List.of("where is", "where s", "wheres", "where are",
        "where can i find", "where do i find", "where can i get", "how do i get to", "how can i get to", "how to get to",
        "take me to", "navigate to", "directions to", "guide me to", "get me to", "go to", "show me", "find me", "find",
        "locate", "search for", "look for", "looking for", "i need", "i want", "i m looking for", "im looking for",
        "is there", "are there", "the", "a", "an", "some", "any", "my", "to"));

    private static final List<String> TRAILING = sortedLongestFirst(List.of("please", "here", "in here", "in this building",
        "located", "pls"));

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Words never corrected and never corrected into (function words and the request phrasing above). */
    private static final Set<String> PROTECTED = new TreeSet<>();
    private static final Map<String, Integer> GROUP_OF = new HashMap<>();

    static {
        for (int g = 0; g < SYNONYMS.size(); g++) {
            for (String s : SYNONYMS.get(g)) {
                GROUP_OF.put(normalize(s), g);
            }
        }
        for (List<String> phrases : List.of(SPATIAL, ACCESSIBILITY, LEADING, TRAILING)) {
            for (String p : phrases) {
                PROTECTED.addAll(List.of(p.split(" ")));
            }
        }
        PROTECTED.addAll(List.of("and", "or", "of", "for", "with", "in", "on", "at", "near", "from", "me", "is", "are", "can"));
    }

    public enum SpatialModifier { NONE, NEAREST }

    /** A word the user typed and what it was corrected to. */
    public record Correction(String from, String to) {}

    /**
     * @param normalized            the whole query, normalised (what is logged and what the lexical fallback matches)
     * @param subject               what is searched for, without modifiers and request phrasing, spelling corrected
     * @param embeddingText         what CLIP embeds: the subject, or -- when reading the query changed nothing but case,
     *                              spacing and punctuation -- the query exactly as typed (lower-cased), so that CLIP sees
     *                              "jacket drop-off" as before, not "jacket drop off"
     * @param typedSubject          the subject as typed (before spelling correction)
     * @param lexicalTerms          the phrasings the lexical match accepts: the subject, the typed subject, and the subject
     *                              with each synonym group member it contains swapped for each of the group's other
     *                              members ("main staircase" -> "main stairs", "main stairway", ...)
     * @param synonymTerms          the terms of lexicalTerms that come from the synonym table, not from the user's words
     * @param synonymGroups         names of the synonym groups the subject contains
     * @param corrections           spelling corrections applied
     * @param spatialModifier       NEAREST when the query asks for the nearest one
     * @param accessibilityRequired the query asks for an accessible (wheelchair, step-free) place
     */
    public record Interpretation(String normalized, String subject, String embeddingText, String typedSubject, List<String> lexicalTerms,
                                 Set<String> synonymTerms, List<String> synonymGroups, List<Correction> corrections,
                                 SpatialModifier spatialModifier, boolean accessibilityRequired) {}

    private QueryInterpreter() {}

    public static String normalize(String query) {
        String s = Normalizer.normalize(query == null ? "" : query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = s.replace('’', '\'').replace('‘', '\'');
        s = NON_WORD.matcher(s).replaceAll(" ");
        return s.strip().replaceAll(" +", " ");
    }

    /**
     * @param venueVocabulary the words of the POI texts in the search's scope (already normalised), the spelling
     *                        corrector's dictionary together with the synonym table; may be empty
     */
    public static Interpretation interpret(String query, Collection<String> venueVocabulary) {
        String normalized = normalize(query);
        String text = " " + normalized + " ";

        boolean accessible = false;
        for (String phrase : ACCESSIBILITY) {
            String padded = " " + phrase + " ";
            if (text.contains(padded)) {
                accessible = true;
                text = text.replace(padded, " ");
            }
        }
        SpatialModifier spatial = SpatialModifier.NONE;
        for (String phrase : sortedLongestFirst(SPATIAL)) {
            String padded = " " + phrase + " ";
            if (text.contains(padded)) {
                spatial = SpatialModifier.NEAREST;
                text = text.replace(padded, " ");
            }
        }
        String subject = strip(text.strip().replaceAll(" +", " "));
        if (subject.isEmpty()) {
            // Nothing but phrasing ("where is it", "nearest"): search the words as typed rather than nothing.
            subject = normalized;
        }
        String typedSubject = subject;

        Set<String> dictionary = new TreeSet<>(GROUP_OF.keySet().stream().flatMap(p -> List.of(p.split(" ")).stream()).toList());
        for (String w : venueVocabulary) {
            if (!w.isBlank()) {
                dictionary.add(w);
            }
        }
        List<Correction> corrections = new ArrayList<>();
        List<String> words = new ArrayList<>(List.of(subject.split(" ")));
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (w.length() < 5 || dictionary.contains(w) || PROTECTED.contains(w) || w.chars().anyMatch(Character::isDigit)
                    || inflectionOf(w, dictionary)) {
                continue;
            }
            String fix = correction(w, dictionary);
            if (fix != null) {
                corrections.add(new Correction(w, fix));
                words.set(i, fix);
            }
        }
        subject = String.join(" ", words);

        // Synonym expansion: every group phrase in the subject (longest first, whole words) swapped for each other member.
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(subject);
        terms.add(typedSubject);
        Set<String> synonymTerms = new TreeSet<>();
        List<String> groups = new ArrayList<>();
        String padded = " " + subject + " ";
        for (String phrase : sortedLongestFirst(new ArrayList<>(GROUP_OF.keySet()))) {
            if (!padded.contains(" " + phrase + " ")) {
                continue;
            }
            List<String> group = SYNONYMS.get(GROUP_OF.get(phrase));
            if (!groups.contains(group.get(0))) {
                groups.add(group.get(0));
            }
            for (String alt : group) {
                String a = normalize(alt);
                if (a.equals(phrase)) {
                    continue;
                }
                String term = (" " + subject + " ").replace(" " + phrase + " ", " " + a + " ").strip();
                if (terms.add(term)) {
                    synonymTerms.add(term);
                }
            }
            padded = padded.replace(" " + phrase + " ", " ");
        }
        String embeddingText = subject.equals(normalized) && corrections.isEmpty()
            ? (query == null ? "" : query.strip().toLowerCase(Locale.ROOT)) : subject;
        return new Interpretation(normalized, subject, embeddingText, typedSubject, List.copyOf(terms), synonymTerms, groups, corrections,
            spatial, accessible);
    }

    /** The synonym group a label belongs to (its normalised text, or failing that its last word), or null. Used to tell two
     * detections of one object under different detector labels ("sofa" and "couch") apart from two different objects. */
    public static String groupOf(String label) {
        String n = normalize(label);
        Integer g = GROUP_OF.get(n);
        if (g == null && n.contains(" ")) {
            g = GROUP_OF.get(n.substring(n.lastIndexOf(' ') + 1));
        }
        return g == null ? null : SYNONYMS.get(g).get(0);
    }

    /** Words of POI texts, for the spelling dictionary. */
    public static Set<String> words(Collection<String> texts) {
        Set<String> out = new TreeSet<>();
        for (String t : texts) {
            for (String w : normalize(t).split(" ")) {
                if (!w.isBlank()) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    private static String strip(String s) {
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            for (String p : LEADING) {
                if (s.equals(p)) {
                    return "";
                }
                if (s.startsWith(p + " ")) {
                    s = s.substring(p.length() + 1);
                    changed = true;
                    break;
                }
            }
            for (String p : TRAILING) {
                if (s.endsWith(" " + p)) {
                    s = s.substring(0, s.length() - p.length() - 1);
                    changed = true;
                    break;
                }
            }
        }
        return s;
    }

    /** w is the singular or plural of a dictionary word ("armchair" next to the venue's "armchairs"): a real word, not a
     * misspelling, so it is not "corrected" into another one. */
    private static boolean inflectionOf(String w, Set<String> dictionary) {
        return dictionary.contains(w + "s") || dictionary.contains(w + "es")
            || (w.endsWith("s") && dictionary.contains(w.substring(0, w.length() - 1)))
            || (w.endsWith("es") && dictionary.contains(w.substring(0, w.length() - 2)));
    }

    /** The single dictionary word within the allowed distance of w, or null when there is none or more than one. */
    private static String correction(String w, Set<String> dictionary) {
        int max = w.length() >= 8 ? 2 : 1;
        int best = Integer.MAX_VALUE;
        List<String> at = new ArrayList<>();
        for (String d : dictionary) {
            if (d.length() < 4 || PROTECTED.contains(d) || Math.abs(d.length() - w.length()) > max) {
                continue;
            }
            int dist = distance(w, d, max);
            if (dist > max) {
                continue;
            }
            if (dist < best) {
                best = dist;
                at.clear();
            }
            if (dist == best) {
                at.add(d);
            }
        }
        return at.size() == 1 ? at.get(0) : null;
    }

    /** Optimal string alignment distance (Damerau-Levenshtein with adjacent transpositions), or max + 1 once it exceeds max. */
    static int distance(String a, String b, int max) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
                rowMin = Math.min(rowMin, d[i][j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
        }
        return d[a.length()][b.length()];
    }

    private static List<String> sortedLongestFirst(List<String> phrases) {
        List<String> out = new ArrayList<>(phrases);
        out.sort((x, y) -> y.length() != x.length() ? y.length() - x.length() : x.compareTo(y));
        return List.copyOf(out);
    }
}
