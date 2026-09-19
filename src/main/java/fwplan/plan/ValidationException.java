package fwplan.plan;

/** 输入格式错误：HTTP 400。 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}
