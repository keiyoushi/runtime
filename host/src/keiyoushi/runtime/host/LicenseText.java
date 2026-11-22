package keiyoushi.runtime.host;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a license file hard-wrapped for an 80-column terminal into paragraphs a phone can wrap:
 * wrapped lines are joined, blank lines stay paragraph breaks, and list items and centred title
 * lines keep their own line. Plain Java so it runs in the runtime's own process.
 */
final class LicenseText {
    /** "1. ", "(a) ", "- ", "* ", "• ", "b) " at the start of a line */
    private static final Pattern LIST_ITEM =
            Pattern.compile("^(?:[-*•]\\s|\\(?[0-9]{1,3}[.)]\\s|\\([a-zA-Z]{1,4}\\)\\s|[a-z][.)]\\s)");

    /** Lines indented this far are centred titles, not wrapped prose */
    private static final int TITLE_INDENT = 12;

    private LicenseText() {}

    static String reflow(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> paragraphs = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        boolean previousOwnLine = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (paragraph.length() > 0) {
                    paragraphs.add(paragraph.toString());
                    paragraph.setLength(0);
                }
                continue;
            }
            boolean ownLine = indent(raw) >= TITLE_INDENT;
            if (paragraph.length() == 0) {
                paragraph.append(line);
            } else if (ownLine || previousOwnLine || LIST_ITEM.matcher(line).lookingAt()) {
                paragraph.append('\n').append(line);
            } else if (endsWithWordHyphen(paragraph)) {
                paragraph.append(line);
            } else {
                paragraph.append(' ').append(line);
            }
            previousOwnLine = ownLine;
        }
        if (paragraph.length() > 0) {
            paragraphs.add(paragraph.toString());
        }
        return String.join("\n\n", paragraphs);
    }

    private static int indent(String line) {
        int columns = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                columns++;
            } else if (c == '\t') {
                columns += 4;
            } else {
                break;
            }
        }
        return columns;
    }

    /** "non-" at the end of a line continues as "non-exclusive", without a space */
    private static boolean endsWithWordHyphen(CharSequence text) {
        int n = text.length();
        return n >= 2 && text.charAt(n - 1) == '-' && Character.isLetter(text.charAt(n - 2));
    }
}
