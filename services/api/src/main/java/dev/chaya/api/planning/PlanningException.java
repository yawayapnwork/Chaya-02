package dev.chaya.api.planning;

/** The planning input cannot be planned from. The code is stable and machine readable. */
public class PlanningException extends RuntimeException {

    private final String code;

    public PlanningException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
