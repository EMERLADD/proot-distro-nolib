package id.or.oo.pr.engine;

final class PdnJson {
    private final String text;
    private int position;
    private PdnJson(String text) { this.text = text; }
    static void validate(String text) {
        PdnJson parser = new PdnJson(text);
        parser.value(0);
        parser.space();
        if (parser.position != text.length()) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid JSON syntax"); }
    private void space() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }
    private boolean take(char value) {
        space();
        if (position < text.length() && text.charAt(position) == value) { position++; return true; }
        return false;
    }
    private void require(char value) { if (!take(value)) throw invalid(); }
    private void value(int depth) {
        if (depth > 64) throw invalid();
        space();
        if (position >= text.length()) throw invalid();
        char value = text.charAt(position);
        if (value == '{') {
            position++;
            if (take('}')) return;
            do { require('"'); string(); require(':'); value(depth + 1); } while (take(','));
            require('}');
        } else if (value == '[') {
            position++;
            if (take(']')) return;
            do { value(depth + 1); } while (take(','));
            require(']');
        } else if (value == '"') { position++; string(); }
        else if (value == 't') literal("true");
        else if (value == 'f') literal("false");
        else if (value == 'n') literal("null");
        else number();
    }
    private void string() {
        while (position < text.length()) {
            char value = text.charAt(position++);
            if (value == '"') return;
            if (value < 32) throw invalid();
            if (value == '\\') {
                if (position >= text.length()) throw invalid();
                value = text.charAt(position++);
                if (value == 'u') {
                    for (int i = 0; i < 4; i++) {
                        if (position >= text.length() || "0123456789abcdefABCDEF".indexOf(text.charAt(position++)) < 0) throw invalid();
                    }
                } else if ("\"\\/bfnrt".indexOf(value) < 0) throw invalid();
            }
        }
        throw invalid();
    }
    private void literal(String value) {
        if (!text.startsWith(value, position)) throw invalid();
        position += value.length();
    }
    private boolean digit() { return position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9'; }
    private void digits() {
        if (!digit()) throw invalid();
        while (digit()) position++;
    }
    private void number() {
        if (position < text.length() && text.charAt(position) == '-') position++;
        if (position >= text.length()) throw invalid();
        if (text.charAt(position) == '0') position++;
        else digits();
        if (position < text.length() && text.charAt(position) == '.') { position++; digits(); }
        if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
            digits();
        }
    }
}
