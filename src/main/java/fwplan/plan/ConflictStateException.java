package fwplan.plan;

/** 状态冲突：HTTP 409（同名包哈希不一致、状态机不允许的推进等）。 */
public class ConflictStateException extends RuntimeException {
    public ConflictStateException(String message) { super(message); }
}
