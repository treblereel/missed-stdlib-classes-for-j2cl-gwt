/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package java.net;

import java.util.Objects;

/**
 * Minimal RFC 3986 compatible URI emulation for GWT/J2CL.
 * Supports: parsing, getters, normalize, resolve, relativize, toString, equals/hashCode.
 * Notes:
 * - Percent-encoding/decoding kept simple and ASCII-oriented.
 * - IPv6 literals in authority are preserved as-is (e.g. "[::1]").
 * - This is not a full drop-in replacement for the JDK class, but API is intentionally close.
 */
public final class URI implements Comparable<URI> {

    private final String scheme;
    private final String authority; // [user-info@]host[:port]
    private final String userInfo;  // parsed from authority
    private final String host;      // parsed from authority (IPv6 literals kept in square brackets)
    private final int port;         // -1 if absent
    private final String path;
    private final String query;     // without leading '?'
    private final String fragment;  // without leading '#'

    private final String raw;       // original textual form (normalized toString() may differ)

    public URI(String str) {
        if (str == null) throw new NullPointerException("URI string is null");
        this.raw = str;
        Parser p = new Parser(str).parse();

        this.scheme = p.scheme;
        this.authority = p.authority;
        this.userInfo = p.userInfo;
        this.host = p.host;
        this.port = p.port;
        this.path = p.path;
        this.query = p.query;
        this.fragment = p.fragment;
    }

    public static URI create(String str) {
        return new URI(str);
    }

    public String getScheme() {
        return scheme;
    }

    public String getAuthority() {
        return authority;
    }

    public String getUserInfo() {
        return userInfo;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public String getFragment() {
        return fragment;
    }

    public boolean isAbsolute() {
        return scheme != null;
    }

    public boolean isOpaque() {
        return false;
    } // this emulation only handles hierarchical URIs

    public URI normalize() {
        if (path == null || path.isEmpty()) return this;
        String norm = collapseDotSegments(path, hasAuthority());
        if (norm.equals(path)) return this;
        return rebuildWith(path(norm));
    }

    public URI resolve(URI ref) {
        if (ref == null) throw new NullPointerException("ref");
        if (ref.scheme != null) {
            return ref.normalize();
        }
        if (ref.authority != null) {
            return new Builder()
                    .scheme(this.scheme)
                    .authority(ref.authority)
                    .path(collapseDotSegments(ref.path, true))
                    .query(ref.query)
                    .fragment(ref.fragment)
                    .build();
        }
        if (ref.path == null || ref.path.isEmpty()) {
            return new Builder()
                    .scheme(this.scheme)
                    .authority(this.authority)
                    .path(this.path)
                    .query(ref.query != null ? ref.query : this.query)
                    .fragment(ref.fragment)
                    .build();
        }
        String merged;
        if (hasAuthority() && (this.path == null || this.path.isEmpty())) {
            merged = "/" + ref.path;
        } else {
            int lastSlash = lastSlashIndex(this.path);
            if (lastSlash >= 0) {
                merged = this.path.substring(0, lastSlash + 1) + ref.path;
            } else {
                merged = ref.path;
            }
        }
        String norm = collapseDotSegments(merged, hasAuthority());
        return new Builder()
                .scheme(this.scheme)
                .authority(this.authority)
                .path(norm)
                .query(ref.query)
                .fragment(ref.fragment)
                .build();
    }

    public URI resolve(String ref) {
        return resolve(new URI(ref));
    }

    public URI relativize(URI other) {
        if (other == null) throw new NullPointerException("other");
        if (!Objects.equals(this.scheme, other.scheme)) return other;
        if (!Objects.equals(this.authority, other.authority)) return other;

        String basePath = this.path == null ? "" : this.path;
        String otherPath = other.path == null ? "" : other.path;

        if (!otherPath.startsWith(basePathPrefixForRel(basePath))) return other;

        String rel = otherPath.substring(basePath.length());
        if (rel.startsWith("/")) rel = rel.substring(1);

        return new Builder()
                .path(rel)
                .query(other.query)
                .fragment(other.fragment)
                .build();
    }

    // ===== String forms =====

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append(':');
        }
        if (authority != null) {
            sb.append("//").append(authority);
        }
        if (path != null) {
            if (authority != null && !path.isEmpty() && !path.startsWith("/")) {
                sb.append('/');
            }
            sb.append(path);
        }
        if (query != null) {
            sb.append('?').append(query);
        }
        if (fragment != null) {
            sb.append('#').append(fragment);
        }
        return sb.toString();
    }

    public String toASCIIString() {
        // For simplicity we assume input already ASCII or percent-encoded;
        // in real JDK this would punycode-encode host etc.
        return toString();
    }

    // ===== Comparable, equals, hashCode =====

    @Override
    public int compareTo(URI o) {
        int c;
        c = cmp(scheme, o.scheme);
        if (c != 0) return c;
        c = cmp(authority, o.authority);
        if (c != 0) return c;
        c = cmp(path, o.path);
        if (c != 0) return c;
        c = cmp(query, o.query);
        if (c != 0) return c;
        return cmp(fragment, o.fragment);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof URI)) return false;
        URI o = (URI) obj;
        return Objects.equals(scheme, o.scheme)
                && Objects.equals(authority, o.authority)
                && Objects.equals(path, o.path)
                && Objects.equals(query, o.query)
                && Objects.equals(fragment, o.fragment);
    }

    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + (scheme == null ? 0 : scheme.hashCode());
        h = 31 * h + (authority == null ? 0 : authority.hashCode());
        h = 31 * h + (path == null ? 0 : path.hashCode());
        h = 31 * h + (query == null ? 0 : query.hashCode());
        h = 31 * h + (fragment == null ? 0 : fragment.hashCode());
        return h;
    }

    private boolean hasAuthority() {
        return authority != null;
    }

    private URI rebuildWith(String newPath) {
        return new Builder()
                .scheme(scheme)
                .authority(authority)
                .path(newPath)
                .query(query)
                .fragment(fragment)
                .build();
    }

    private static int cmp(String a, String b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private static int lastSlashIndex(String p) {
        if (p == null) return -1;
        return p.lastIndexOf('/');
    }

    private static String path(String p) {
        return p == null ? "" : p;
    }

    private static String basePathPrefixForRel(String base) {
        if (base.isEmpty()) return "";
        return base.endsWith("/") ? base : base.substring(0, Math.max(0, base.lastIndexOf('/') + 1));
    }

    private static String collapseDotSegments(String input, boolean absoluteBase) {
        if (input == null || input.isEmpty()) return "";
        String[] segs = input.split("/", -1);
        String[] stack = new String[segs.length];
        int top = 0;

        for (String s : segs) {
            if (s.equals("") || s.equals(".")) {
                // skip empty (caused by leading // or // between) and dot
                if (s.equals("") && top == 0 && absoluteBase) {
                    // preserve leading empty to keep absolute path when authority present
                    stack[top++] = "";
                }
                continue;
            } else if (s.equals("..")) {
                if (top > 0) {
                    // pop unless it is the leading empty that encodes absolute
                    if (!(top == 1 && "".equals(stack[0]) && absoluteBase)) {
                        top--;
                        continue;
                    }
                }
                // cannot go above root -> keep ".."
                stack[top++] = "..";
            } else {
                stack[top++] = s;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < top; i++) {
            String s = stack[i];
            if (i > 0) out.append('/');
            else if ("".equals(s)) out.append('/'); // absolute
            out.append(s);
        }
        return out.length() == 0 ? (absoluteBase ? "/" : "") : out.toString();
    }

    public static String encodeComponent(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (isUnreserved(ch)) {
                sb.append(ch);
            } else {
                byte[] bytes = String.valueOf(ch).getBytes(); // J2CL char→UTF-8 via JS runtime
                for (byte b : bytes) {
                    sb.append('%');
                    sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
        }
        return sb.toString();
    }

    public static String decode(String s) {
        if (s == null) return null;
        int n = s.length();
        byte[] buf = new byte[n];
        int bi = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < n) {
                int hi = hex(s.charAt(++i));
                int lo = hex(s.charAt(++i));
                if (hi >= 0 && lo >= 0) {
                    buf[bi++] = (byte) ((hi << 4) + lo);
                    continue;
                }
                // fallthrough if bad escape
                i -= 2;
            }
            // write as bytes of this char
            byte[] bytes = String.valueOf(c).getBytes();
            for (byte b : bytes) buf[bi++] = b;
        }
        return new String(copyOf(buf, bi));
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') ||
                c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        return -1;
    }

    private static byte[] copyOf(byte[] src, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = src[i];
        return out;
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static final class Builder {
        String scheme, authority, path, query, fragment;

        Builder scheme(String v) {
            this.scheme = v;
            return this;
        }

        Builder authority(String v) {
            this.authority = v;
            return this;
        }

        Builder path(String v) {
            this.path = v;
            return this;
        }

        Builder query(String v) {
            this.query = v;
            return this;
        }

        Builder fragment(String v) {
            this.fragment = v;
            return this;
        }

        URI build() {
            String a = authority;
            String u = null, h = null;
            int p = -1;
            if (a != null) {
                int at = a.indexOf('@');
                String rest = a;
                if (at >= 0) {
                    u = a.substring(0, at);
                    rest = a.substring(at + 1);
                }
                if (rest.startsWith("[")) {
                    // IPv6 literal [....]
                    int rb = rest.indexOf(']');
                    if (rb > 0) {
                        h = rest.substring(0, rb + 1);
                        if (rb + 1 < rest.length() && rest.charAt(rb + 1) == ':') {
                            p = parsePort(rest.substring(rb + 2));
                        }
                    } else {
                        h = rest; // malformed but keep as-is
                    }
                } else {
                    int colon = rest.lastIndexOf(':');
                    if (colon > 0) {
                        h = rest.substring(0, colon);
                        p = parsePort(rest.substring(colon + 1));
                    } else {
                        h = rest;
                    }
                }
            }
            return new URI(
                    compose(scheme, a, path, query, fragment),
                    scheme, a, u, h, p,
                    path == null ? "" : path, query, fragment
            );
        }
    }

    private URI(
            String raw,
            String scheme,
            String authority,
            String userInfo,
            String host,
            int port,
            String path,
            String query,
            String fragment) {
        this.raw = raw;
        this.scheme = scheme;
        this.authority = authority;
        this.userInfo = userInfo;
        this.host = host;
        this.port = port;
        this.path = path;
        this.query = query;
        this.fragment = fragment;
    }

    private static String compose(String scheme, String authority, String path, String query, String fragment) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (authority != null) sb.append("//").append(authority);
        if (path != null) {
            if (authority != null && !path.isEmpty() && !path.startsWith("/")) sb.append('/');
            sb.append(path);
        }
        if (query != null) sb.append('?').append(query);
        if (fragment != null) sb.append('#').append(fragment);
        return sb.toString();
    }

    private static int parsePort(String s) {
        if (s == null || s.isEmpty()) return -1;
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
            if (v > 65535) return -1;
        }
        return v;
    }

    // ===== Parser =====

    private static final class Parser {
        final String s;
        String scheme, authority, path, query, fragment;
        String userInfo, host;
        int port = -1;

        Parser(String s) {
            this.s = s;
        }

        Parser parse() {
            int end = s.length();

            int hash = idx('#', 0, end);
            if (hash >= 0) {
                fragment = s.substring(hash + 1);
                end = hash;
            }

            int colon = idx(':', 0, end);
            if (colon >= 0 && isValidScheme(s, 0, colon)) {
                scheme = s.substring(0, colon);
                int ssp = colon + 1;

                // 3) //authority ?
                if (ssp + 1 < end && s.startsWith("//", ssp)) {
                    int authStart = ssp + 2;
                    int pathStart = idx('/', authStart, end);
                    int qMark = idx('?', authStart, end);
                    int split = minPositive(pathStart, qMark, end);

                    authority = s.substring(authStart, split);
                    parseAuthority();

                    if (split < end && s.charAt(split) == '/') {
                        int qs = idx('?', split, end);
                        path = s.substring(split, (qs >= 0 ? qs : end));
                        if (qs >= 0) query = s.substring(qs + 1, end);
                    } else if (split < end && s.charAt(split) == '?') {
                        path = "";
                        query = s.substring(split + 1, end);
                    } else {
                        path = "";
                    }
                    return this;
                }

                int qs = idx('?', ssp, end);
                path = s.substring(ssp, (qs >= 0 ? qs : end));
                if (qs >= 0) query = s.substring(qs + 1, end);
                return this;
            }

            int start = 0;
            if (start + 1 < end && s.startsWith("//", start)) {
                int authStart = start + 2;
                int pathStart = idx('/', authStart, end);
                int qMark = idx('?', authStart, end);
                int split = minPositive(pathStart, qMark, end);

                authority = s.substring(authStart, split);
                parseAuthority();

                if (split < end && s.charAt(split) == '/') {
                    int qs = idx('?', split, end);
                    path = s.substring(split, (qs >= 0 ? qs : end));
                    if (qs >= 0) query = s.substring(qs + 1, end);
                } else if (split < end && s.charAt(split) == '?') {
                    path = "";
                    query = s.substring(split + 1, end);
                } else {
                    path = "";
                }
                return this;
            }

            int qs = idx('?', start, end);
            path = s.substring(start, (qs >= 0 ? qs : end));
            if (qs >= 0) query = s.substring(qs + 1, end);
            return this;
        }

        private void parseAuthority() {
            if (authority == null) return;
            String a = authority;
            int at = a.indexOf('@');
            String rem = a;
            if (at >= 0) {
                userInfo = a.substring(0, at);
                rem = a.substring(at + 1);
            }
            if (rem.startsWith("[")) {
                int rb = rem.indexOf(']');
                if (rb > 0) {
                    host = rem.substring(0, rb + 1);
                    if (rb + 1 < rem.length() && rem.charAt(rb + 1) == ':') {
                        port = parsePort(rem.substring(rb + 2));
                    }
                } else {
                    host = rem;
                }
            } else {
                int colon = rem.lastIndexOf(':');
                if (colon > 0) {
                    host = rem.substring(0, colon);
                    port = parsePort(rem.substring(colon + 1));
                } else {
                    host = rem;
                }
            }
        }

        private static boolean isValidScheme(String s, int start, int end) {
            if (start >= end) return false;
            char c0 = s.charAt(start);
            if (!isAlpha(c0)) return false;
            for (int i = start + 1; i < end; i++) {
                char c = s.charAt(i);
                if (!(isAlpha(c) || isDigit(c) || c == '+' || c == '-' || c == '.')) return false;
            }
            return true;
        }

        private static boolean isAlpha(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private int idx(char ch, int from, int toExclusive) {
            int to = Math.min(toExclusive, s.length());
            for (int i = Math.max(0, from); i < to; i++) {
                if (s.charAt(i) == ch) return i;
            }
            return -1;
        }

        private static int minPositive(int a, int b, int defVal) {
            if (a < 0 && b < 0) return defVal;
            if (a < 0) return b;
            if (b < 0) return a;
            return Math.min(a, b);
        }
    }

    private static int indexOf(char ch, int from, int toExclusive, String s) {
        int end = Math.min(toExclusive, s.length());
        for (int i = Math.max(0, from); i < end; i++) {
            if (s.charAt(i) == ch) return i;
        }
        return -1;
    }

    private static int indexOf(char ch, int from, int toExclusive) {
        throw new AssertionError("unreachable"); // kept to avoid accidental call
    }
}
