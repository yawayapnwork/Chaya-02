package dev.chaya.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.chaya.api.search.QueryInterpreter.Correction;
import dev.chaya.api.search.QueryInterpreter.Interpretation;
import dev.chaya.api.search.QueryInterpreter.SpatialModifier;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** QueryInterpreter's rules (docs/search.md, "Query understanding"). Pure: no database, no model. */
class QueryInterpreterTest {

    private static final Set<String> VENUE = QueryInterpreter.words(List.of("Reception desk service front desk",
        "Main staircase stairs", "Elevator A elevator lift", "Cafe food coffee", "Lounge seating area sofas", "Gift shop"));

    private static Interpretation read(String q) {
        return QueryInterpreter.interpret(q, VENUE);
    }

    @Test
    void normalisesCaseUnicodePunctuationAndSpacing() {
        assertThat(QueryInterpreter.normalize("  Where’s   the RECEPTION?? ")).isEqualTo("where s the reception");
        assertThat(QueryInterpreter.normalize("Wheelchair-accessible\tentrance")).isEqualTo("wheelchair accessible entrance");
        assertThat(QueryInterpreter.normalize("ＣＡＦＥ")).as("NFKC folds full-width letters").isEqualTo("cafe");
    }

    @Test
    void theFiveExampleQueriesReadAsTheirSubjectAndModifiers() {
        Interpretation stairs = read("Find the nearest staircase.");
        assertThat(stairs.subject()).isEqualTo("staircase");
        assertThat(stairs.spatialModifier()).isEqualTo(SpatialModifier.NEAREST);
        assertThat(stairs.synonymGroups()).containsExactly("stairs");
        assertThat(stairs.lexicalTerms()).contains("staircase", "stairs", "stairway");

        Interpretation reception = read("Where is the reception?");
        assertThat(reception.subject()).isEqualTo("reception");
        assertThat(reception.spatialModifier()).isEqualTo(SpatialModifier.NONE);
        assertThat(reception.lexicalTerms()).contains("reception desk", "front desk");

        Interpretation elevator = read("Show me the closest elevator");
        assertThat(elevator.subject()).isEqualTo("elevator");
        assertThat(elevator.spatialModifier()).isEqualTo(SpatialModifier.NEAREST);
        assertThat(elevator.lexicalTerms()).contains("lift");

        Interpretation entrance = read("Find a wheelchair-accessible entrance");
        assertThat(entrance.subject()).isEqualTo("entrance");
        assertThat(entrance.accessibilityRequired()).isTrue();
        assertThat(entrance.spatialModifier()).isEqualTo(SpatialModifier.NONE);

        Interpretation sofa = read("Where is the sofa?");
        assertThat(sofa.subject()).isEqualTo("sofa");
        assertThat(sofa.lexicalTerms()).contains("couch", "settee");
        assertThat(sofa.embeddingText()).as("CLIP embeds the subject, not the question").isEqualTo("sofa");
    }

    @Test
    void aQueryThatNeedsNoReadingIsEmbeddedExactlyAsTyped() {
        // Normalisation is for matching; CLIP keeps seeing punctuation as it always did.
        Interpretation r = read("Jacket drop-off");
        assertThat(r.subject()).isEqualTo("jacket drop off");
        assertThat(r.embeddingText()).isEqualTo("jacket drop-off");
    }

    @Test
    void spellingIsCorrectedOnlyToAUniqueCloseVenueOrTableWord() {
        assertThat(read("recepton desk").corrections()).containsExactly(new Correction("recepton", "reception"));
        assertThat(read("recepton desk").subject()).isEqualTo("reception desk");
        assertThat(read("staircse").subject()).isEqualTo("staircase");
        assertThat(read("elevater").subject()).isEqualTo("elevator");
        // Short words are never corrected: "gift" is not a misspelt "lift".
        assertThat(read("gift").corrections()).isEmpty();
        // A word of the venue is never corrected, even if it is near another one.
        assertThat(read("sofas").corrections()).isEmpty();
        // Nor the singular or plural of one: "armchair" is a word, not a misspelt "armchairs".
        assertThat(QueryInterpreter.interpret("brown armchair", QueryInterpreter.words(List.of("Lounge armchairs"))).corrections())
            .isEmpty();
        // Unrelated words stay as typed.
        assertThat(read("swimming pool").corrections()).isEmpty();
        assertThat(read("swimming pool").subject()).isEqualTo("swimming pool");
    }

    @Test
    void aCorrectionIsNeverAGuessBetweenTwoEquallyCloseWords() {
        Set<String> venue = QueryInterpreter.words(List.of("Bench", "Beach bar"));
        // "beech" is one edit from both "bench" and "beach".
        assertThat(QueryInterpreter.interpret("beech", venue).corrections()).isEmpty();
    }

    @Test
    void accessibilityIsReadFromTheQueryNeverInventedAndStepFreeCounts() {
        assertThat(read("step-free route to the cafe").accessibilityRequired()).isTrue();
        assertThat(read("accessible restroom").subject()).isEqualTo("restroom");
        assertThat(read("restroom").accessibilityRequired()).isFalse();
    }

    @Test
    void nearestNeedsTheWordNotAGuess() {
        assertThat(read("cafe near me").spatialModifier()).isEqualTo(SpatialModifier.NEAREST);
        assertThat(read("cafe near me").subject()).isEqualTo("cafe");
        assertThat(read("cafe").spatialModifier()).isEqualTo(SpatialModifier.NONE);
    }

    @Test
    void onlyPhrasingLeftSearchesTheWordsAsTyped() {
        assertThat(read("where is").subject()).isEqualTo("where is");
    }

    @Test
    void synonymsSwapWithinAPhraseAndKeepTheRestOfIt() {
        Interpretation r = read("main staircase");
        assertThat(r.lexicalTerms()).contains("main staircase", "main stairs", "main stairway");
        assertThat(r.lexicalTerms()).as("not the bare synonym: 'main staircase' is one particular staircase").doesNotContain("stairs");
        assertThat(r.synonymTerms()).contains("main stairs").doesNotContain("main staircase");
    }

    @Test
    void groupsTellTwoLabelsOfOneObjectApartFromTwoObjects() {
        assertThat(QueryInterpreter.groupOf("couch")).isEqualTo(QueryInterpreter.groupOf("Sofa")).isEqualTo("sofa");
        assertThat(QueryInterpreter.groupOf("chair")).isNotEqualTo(QueryInterpreter.groupOf("sofa"));
        assertThat(QueryInterpreter.groupOf("armchair")).as("a kind of chair is not a synonym of chair").isNull();
        assertThat(QueryInterpreter.groupOf("exit sign")).isNull();
    }

    @Test
    void theSameQueryAlwaysReadsTheSameWay() {
        for (String q : List.of("Find the nearest staircase", "where's the recepton", "wheelchair accessible entrance")) {
            assertThat(read(q)).isEqualTo(read(q));
        }
    }

    @Test
    void editDistanceCountsAnAdjacentSwapAsOneEdit() {
        assertThat(QueryInterpreter.distance("recieve", "receive", 2)).isEqualTo(1);
        assertThat(QueryInterpreter.distance("cat", "dog", 1)).isEqualTo(2);
    }
}
