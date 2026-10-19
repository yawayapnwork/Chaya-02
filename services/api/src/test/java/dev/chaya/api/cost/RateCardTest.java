package dev.chaya.api.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** RateCard: what the operator configures, refused at startup when it cannot be priced with honestly. */
class RateCardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static final String CARD = """
        {"currency": "USD",
         "workerClasses": {"gpu-g5-xlarge": {"hourly": 1.006, "kind": "CLOUD_LIST_PRICE", "source": "AWS on-demand us-east-1 2026-10"},
                           "local-rtx4090": {"hourly": 0.12, "kind": "LOCAL_HARDWARE", "source": "450 W at 0.27/kWh"}},
         "storagePerGbMonth": {"rate": 0.023, "source": "S3 Standard us-east-1"}}""";

    @Test
    void noCardMeansNothingIsPriced() {
        assertThat(RateCard.parse(null, mapper)).isNull();
        assertThat(RateCard.parse("  ", mapper)).isNull();
    }

    @Test
    void aValidCardIsReadWithEveryRateAndItsSource() {
        RateCard card = RateCard.parse(CARD, mapper);
        assertThat(card.currency()).isEqualTo("USD");
        assertThat(card.workerClasses().get("gpu-g5-xlarge").hourly()).isEqualByComparingTo(new BigDecimal("1.006"));
        assertThat(card.workerClasses().get("local-rtx4090").kind()).isEqualTo("LOCAL_HARDWARE");
        assertThat(card.storage().perGbMonth()).isEqualByComparingTo("0.023");
        assertThat(card.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void theHashIdentifiesTheRatesNotTheirFormatting() {
        String reordered = """
            {"storagePerGbMonth": {"source": "S3 Standard us-east-1", "rate": 0.0230},
             "workerClasses": {"local-rtx4090": {"source": "450 W at 0.27/kWh", "kind": "LOCAL_HARDWARE", "hourly": 0.12},
                               "gpu-g5-xlarge": {"kind": "CLOUD_LIST_PRICE", "hourly": 1.006, "source": "AWS on-demand us-east-1 2026-10"}},
             "currency": "USD"}""";
        assertThat(RateCard.parse(reordered, mapper).sha256()).isEqualTo(RateCard.parse(CARD, mapper).sha256());
        String changed = CARD.replace("1.006", "1.007");
        assertThat(RateCard.parse(changed, mapper).sha256()).isNotEqualTo(RateCard.parse(CARD, mapper).sha256());
    }

    @Test
    void anInvalidCardStopsStartupWithTheReason() {
        assertInvalid("{", "not valid JSON");
        assertInvalid("[]", "JSON object");
        assertInvalid(CARD.replace("\"USD\"", "\"usd\""), "currency");
        assertInvalid(CARD.replace("1.006", "-1"), "between 0");
        assertInvalid(CARD.replace("\"hourly\": 0.12", "\"hourly\": \"0.12\""), "must be a number");
        assertInvalid(CARD.replace("CLOUD_LIST_PRICE", "GUESS"), "kind must be one of");
        assertInvalid(CARD.replace("\"source\": \"AWS on-demand us-east-1 2026-10\"", "\"source\": \" \""), "source");
        assertInvalid(CARD.replace("\"currency\"", "\"discount\": 0.5, \"currency\""), "unknown field discount");
        assertInvalid("{\"currency\": \"USD\", \"workerClasses\": {}}", "at least one");
        assertInvalid(CARD.replace("gpu-g5-xlarge", "gpu g5"), "worker class");
    }

    private void assertInvalid(String json, String why) {
        assertThatThrownBy(() -> RateCard.parse(json, mapper)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CHAYA_COST_RATES").hasMessageContaining(why);
    }
}
