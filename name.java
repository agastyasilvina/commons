public record CoreName(String name1, String name2) {

    public static CoreName of(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() <= 40) return new CoreName(n, n);   // fits: same name in both
        return new CoreName(n.substring(0, 40), n.substring(40, Math.min(n.length(), 80)));
    }
}
