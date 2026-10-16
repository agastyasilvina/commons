public record CoreAddress(String address1, String address2, String address3, String address4) {

    private static final int MAX_LEN = 40;

    public static CoreAddress of(String street, String subdistrict, String district, String postcode) {
        String[] lines = splitStreet(street);
        String address4 = (subdistrict == null || district == null || postcode == null)
                ? "-"
                : subdistrict + ", " + district + " " + postcode;
        return new CoreAddress(lines[0], lines[1], lines[2], address4);
    }

    // up to 3 lines of max 40 chars, breaking at spaces; unused lines are ""
    private static String[] splitStreet(String street) {
        String[] lines = {"", "", ""};
        String rest = street == null ? "" : street.trim();
        for (int i = 0; i < 3 && !rest.isEmpty(); i++) {
            int cut = rest.length() <= MAX_LEN ? rest.length() : rest.lastIndexOf(' ', MAX_LEN);
            if (cut <= 0) cut = MAX_LEN; // no space to break at
            lines[i] = rest.substring(0, cut).trim();
            rest = rest.substring(cut).trim();
        }
        return lines;
    }
}
