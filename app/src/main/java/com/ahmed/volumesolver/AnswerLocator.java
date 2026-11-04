package com.ahmed.volumesolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the option that a model named, among the pieces of text on the screen, and says where to tap.
 * <p>
 * Used for DeepSeek, whose documentation does not promise bounding boxes: it reads the answer from the screenshot
 * well, but the position it reports for the option cannot be trusted. The position of a piece of text comes from the
 * accessibility tree instead, which is exact. Pure Java, so that it can be tested without a phone.
 */
final class AnswerLocator {

    /** One piece of text on the screen. */
    static final class Item {
        final String text;
        /** The area to tap: the button the text belongs to when there is one, else the bounds of the text itself. */
        final int left;
        final int top;
        final int right;
        final int bottom;
        /** True when {@link #left}.. is a clickable view (the text, or a view around it, accepts clicks). */
        final boolean clickable;

        Item(String text, int left, int top, int right, int bottom, boolean clickable) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.clickable = clickable;
        }

        float centerX() {
            return (left + right) / 2f;
        }

        float centerY() {
            return (top + bottom) / 2f;
        }

        boolean sameArea(Item o) {
            return left == o.left && top == o.top && right == o.right && bottom == o.bottom;
        }

        String describe() {
            String t = text.length() > 60 ? text.substring(0, 60) + "..." : text;
            return "\"" + t.replace('\n', ' ') + "\" [" + left + "," + top + " - " + right + "," + bottom + "]"
                    + (clickable ? " clickable" : "");
        }
    }

    /** The outcome: where to tap (or null) and what to write in the log either way. */
    static final class Result {
        final Item item;
        final String log;

        Result(Item item, String log) {
            this.item = item;
            this.log = log;
        }
    }

    private AnswerLocator() {
    }

    /**
     * @param items  the text on the screen
     * @param answer what the model said the correct option is
     * @param boxX   centre of the box the model gave, in pixels (NaN if it gave none); only used to choose between
     *               several pieces of text that match equally well
     * @param boxY   same, vertical
     */
    static Result find(List<Item> items, String answer, double boxX, double boxY) {
        List<String> wanted = forms(answer);
        if (wanted.isEmpty()) {
            return new Result(null, "The answer \"" + answer + "\" is too short to look for on the screen (a single "
                    + "letter or number would match many things).");
        }
        List<Item> exact = new ArrayList<>();
        List<Item> similar = new ArrayList<>();
        for (Item item : items) {
            int quality = match(wanted, forms(item.text));
            if (quality == 2) {
                addOnce(exact, item);
            } else if (quality == 1) {
                addOnce(similar, item);
            }
        }
        String kind = "text equal to the answer";
        List<Item> candidates = exact;
        if (candidates.isEmpty()) {
            candidates = similar;
            kind = "text similar to the answer";
        }
        String seen = items.size() + (items.size() == 1 ? " piece" : " pieces") + " of text on the screen";
        if (candidates.isEmpty()) {
            return new Result(null, "Looked for the answer \"" + answer + "\" among " + seen + ": nothing matches.");
        }
        if (candidates.size() == 1) {
            Item only = candidates.get(0);
            return new Result(only, "Found the answer \"" + answer + "\" on the screen (" + kind + ", among " + seen
                    + "): " + only.describe());
        }

        // Several matches: the options are buttons, the question usually is not.
        List<Item> buttons = new ArrayList<>();
        for (Item c : candidates) {
            if (c.clickable) {
                buttons.add(c);
            }
        }
        String how = "";
        if (!buttons.isEmpty() && buttons.size() < candidates.size()) {
            candidates = buttons;
            how = " Only the clickable ones were kept.";
        }
        if (candidates.size() == 1) {
            Item only = candidates.get(0);
            return new Result(only, "Found the answer \"" + answer + "\" on the screen (" + kind + ", among " + seen
                    + "; it appears several times, " + how.trim() + "): " + only.describe());
        }
        if (Double.isNaN(boxX) || Double.isNaN(boxY)) {
            return new Result(null, "The answer \"" + answer + "\" appears " + candidates.size() + " times on the "
                    + "screen and the model gave no box to choose between them:" + list(candidates));
        }
        Item best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Item c : candidates) {
            double dx = c.centerX() - boxX;
            double dy = c.centerY() - boxY;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return new Result(best, "The answer \"" + answer + "\" appears " + candidates.size() + " times on the screen."
                + how + " Took the one nearest to the box the model gave: " + best.describe() + ". All of them:"
                + list(candidates));
    }

    private static String list(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) {
            sb.append("\n  - ").append(i.describe());
        }
        return sb.toString();
    }

    /** Two entries for the same view (a button and its label) are one candidate. */
    private static void addOnce(List<Item> list, Item item) {
        for (Item existing : list) {
            if (existing.sameArea(item)) {
                return;
            }
        }
        list.add(item);
    }

    /** 2 when one of the forms is the same text, 1 when a form of the item is nearly the answer, else 0. */
    private static int match(List<String> wanted, List<String> have) {
        int best = 0;
        for (String w : wanted) {
            for (String h : have) {
                if (h.isEmpty()) {
                    continue;
                }
                if (h.equals(w)) {
                    return 2;
                }
                // "Paris, France" for "Paris", or "The Eiffel Tower in Paris" for "The Eiffel Tower": nearly the
                // same length. A long paragraph that happens to contain the answer is not the option.
                if (w.length() >= 4 && h.length() >= 4) {
                    boolean haveContainsWanted = h.contains(w) && h.length() <= w.length() * 1.5 + 10;
                    boolean wantedContainsHave = w.contains(h) && w.length() <= h.length() * 1.5 + 10;
                    if (haveContainsWanted || wantedContainsHave) {
                        best = 1;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The comparable forms of a text: the text itself and the text without a leading option label ("B.", "(c)",
     * "2)"). Empty when the text is only one character, which would match too much.
     */
    static List<String> forms(String text) {
        List<String> out = new ArrayList<>();
        String full = normalize(text);
        if (full.length() >= 2) {
            out.add(full);
        }
        String stripped = normalize(stripLabel(text));
        if (stripped.length() >= 2 && !out.contains(stripped)) {
            out.add(stripped);
        }
        return out;
    }

    /** "B. Paris" -> "Paris", "(c) Paris" -> "Paris", "2) Paris" -> "Paris". */
    static String stripLabel(String text) {
        return text.trim().replaceFirst("^[(\\[]?[\\p{L}\\p{N}]{1,2}[)\\].:\\-–]\\s+", "");
    }

    /** Lower case, letters and digits only, one space between words; accents and Arabic marks are dropped. */
    static String normalize(String text) {
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || c == 'ـ' /* Arabic tatweel */ || c == '‎' || c == '‏') {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                if (space && sb.length() > 0) {
                    sb.append(' ');
                }
                space = false;
                sb.append(c);
            } else {
                space = true;
            }
        }
        return sb.toString();
    }
}
