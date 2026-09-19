package fw.api;

/** Error categories let clients distinguish input, state and internal faults. */
public final class Errors {

    private Errors() {
    }

    public abstract static class ApiException extends RuntimeException {
        private final String code;

        ApiException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }

        public abstract int status();
    }

    /** Malformed / syntactically or semantically invalid client input. */
    public static final class BadRequest extends ApiException {
        public BadRequest(String message) {
            super("invalid_input", message);
        }

        public BadRequest(String code, String message) {
            super(code, message);
        }

        @Override
        public int status() {
            return 400;
        }
    }

    /** Valid input but conflicts with the current durable state. */
    public static final class StateConflict extends ApiException {
        public StateConflict(String message) {
            super("state_conflict", message);
        }

        @Override
        public int status() {
            return 409;
        }
    }

    public static final class NotFound extends ApiException {
        public NotFound(String message) {
            super("not_found", message);
        }

        @Override
        public int status() {
            return 404;
        }
    }

    /** Unexpected server-side failure; never carries planner logic. */
    public static final class Internal extends ApiException {
        public Internal(String message, Throwable cause) {
            super("internal_fault", message);
            initCause(cause);
        }

        @Override
        public int status() {
            return 500;
        }
    }
}
