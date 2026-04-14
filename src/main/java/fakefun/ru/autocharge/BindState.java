package fakefun.ru.autocharge;

record BindState(BindType type, int code) {
    static BindState unbound() {
        return new BindState(BindType.NONE, -1);
    }

    boolean isBound() {
        return type != BindType.NONE && code >= 0;
    }
}
