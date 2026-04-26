package dev.backup.incrementalbackup.util;

import java.util.regex.Pattern;

/**
 * Simple glob matcher supporting * ** and ? patterns.
 */
public class GlobMatcher {

    public static boolean matches(String path, String glob) {
        String regex = globToRegex(glob);
        return Pattern.compile(regex).matcher(path).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '/') i++;
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (".+^${}()|[]\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        sb.append('$');
        return sb.toString();
    }
}
