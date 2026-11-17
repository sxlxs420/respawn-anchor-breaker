package com.respawnanchorbreaker;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads new lines from the chat window, including Baritone's own messages. Baritone writes straight into the chat
 * component (not through the network), so no message event fires for it. The chat's message list is read through
 * reflection so a field rename in a future Minecraft version just disables this feature instead of breaking the build.
 */
public final class ChatWatcher {
    private static Field listField;
    private static Method contentMethod;
    private static Method getChat;
    private static boolean broken;
    private static Object lastNewest;

    private ChatWatcher() { }

    public static void poll(Minecraft mc, Consumer<String> sink) {
        if (broken || mc.gui == null) return;
        try {
            if (getChat == null) getChat = mc.gui.getClass().getMethod("getChat");
            Object chat = getChat.invoke(mc.gui);
            if (chat == null) return;
            if (listField == null) listField = findListField(chat.getClass());
            if (listField == null) throw new IllegalStateException("chat message list not found");
            List<?> list = (List<?>) listField.get(chat);
            if (list == null || list.isEmpty()) {
                lastNewest = null;
                return;
            }
            Object newest = list.get(0); // newest first
            if (newest == lastNewest) return;
            if (lastNewest == null) { // first look: don't replay old chat history
                lastNewest = newest;
                return;
            }
            List<Object> fresh = new ArrayList<>();
            for (int i = 0; i < list.size() && i < 20; i++) {
                Object m = list.get(i);
                if (m == lastNewest) break;
                fresh.add(m);
            }
            lastNewest = newest;
            for (int i = fresh.size() - 1; i >= 0; i--) { // oldest first
                String text = textOf(fresh.get(i));
                if (text != null && !text.isEmpty()) sink.accept(text);
            }
        } catch (Exception e) {
            broken = true;
            ActivityLog.warn("Navigation", "Can't read Baritone chat messages in this Minecraft version ("
                    + e.getClass().getSimpleName() + "). Chat-based failure detection is off; stall detection still works.");
        }
    }

    private static Field findListField(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            String g = f.getGenericType().getTypeName();
            if (g.endsWith(".GuiMessage>") && !g.contains("Line")) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private static String textOf(Object guiMessage) throws Exception {
        if (contentMethod == null) contentMethod = guiMessage.getClass().getMethod("content");
        Object c = contentMethod.invoke(guiMessage);
        return c instanceof Component comp ? comp.getString() : null;
    }
}
