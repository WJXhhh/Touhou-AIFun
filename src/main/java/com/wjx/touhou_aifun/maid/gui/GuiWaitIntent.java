package com.wjx.touhou_aifun.maid.gui;

import java.util.regex.Pattern;

/** Small guard for explicit stop-wait instructions; remaining intent is resolved by the model. */
public final class GuiWaitIntent {
    private static final Pattern NO_WAIT = Pattern.compile("不用等|不要等|不必等|无需等|别等|放进去就走|投料后就走|不需要等|do\\s+not\\s+wait|don't\\s+wait|no\\s+need\\s+to\\s+wait", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNTIL_GOAL = Pattern.compile("等(?:它|到|待)?(?:烧完|做完|做好|加工完|完成)|(?:烧完|做好|做完|加工完).*?(?:拿|取|给我)|wait\\s+(?:until|for).*?(?:done|finish|complete)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEADLINE = Pattern.compile("(?:最多|至多|不超过|上限|最多等|at\\s+most|no\\s+more\\s+than)\\s*(?:等|等待|wait)?\\s*([0-9]+|[零一二两三四五六七八九十百]+)\\s*(秒|分钟|分|seconds?|secs?|minutes?|mins?)", Pattern.CASE_INSENSITIVE);
    private GuiWaitIntent() { }
    public static GuiWaitPolicy resolve(String userText, GuiWaitPolicy proposed) {
        String text = userText == null ? "" : userText;
        if (NO_WAIT.matcher(text).find()) return GuiWaitPolicy.NO_WAIT;
        return UNTIL_GOAL.matcher(text).find() ? GuiWaitPolicy.UNTIL_GOAL : proposed;
    }
    public static int maximumSeconds(String text) {
        var matcher = DEADLINE.matcher(text == null ? "" : text);
        if (!matcher.find()) return 0;
        String number = matcher.group(1);
        int value = 0, digit = 0;
        if (number.matches("[0-9]+")) {
            try { value = Integer.parseInt(number); } catch (NumberFormatException ignored) { value = 86400; }
        } else {
            for (char c : number.toCharArray()) {
                if (c == '十' || c == '百') { value += Math.max(1, digit) * (c == '十' ? 10 : 100); digit = 0; }
                else digit = c == '两' ? 2 : "零一二三四五六七八九".indexOf(c);
            }
            value += digit;
        }
        String unit = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        return (int) Math.min(86400, (long) value * (unit.equals("秒") || unit.startsWith("s") ? 1 : 60));
    }
}
