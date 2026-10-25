import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.util.List;
import org.xmlpull.v1.XmlSerializer;

/** Captura de leitura: espera as janelas e ignora filhos que desaparecem durante a consulta. */
public final class ReleaseUiDump {
    private static int count;
    public static void main(String[] args) throws Exception {
        HandlerThread thread = new HandlerThread("release-dump");
        thread.start();
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        Constructor<?> ctor = UiAutomation.class.getDeclaredConstructor(Looper.class, Class.forName("android.app.IUiAutomationConnection"));
        UiAutomation ui = (UiAutomation) ctor.newInstance(thread.getLooper(), connection);
        UiAutomation.class.getMethod("connect").invoke(ui);
        try {
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            ui.setServiceInfo(info);
            for (int attempt = 0; attempt < 10; attempt++) {
                Thread.sleep(500);
                ui.clearCache();
                List<AccessibilityWindowInfo> windows = ui.getWindows();
                count = 0;
                boolean application = false;
                try (FileOutputStream out = new FileOutputStream(args[0])) {
                    XmlSerializer xml = Xml.newSerializer();
                    xml.setOutput(out, "UTF-8");
                    xml.startDocument("UTF-8", true);
                    xml.startTag("", "displays");
                    for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) continue;
                        xml.startTag("", "window");
                        String type = window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION ? "TYPE_APPLICATION" : "OTHER";
                        xml.attribute("", "type", type);
                        int before = count;
                        dump(xml, root, 0);
                        application |= window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && count - before > 4;
                        xml.endTag("", "window");
                    }
                    xml.endTag("", "displays");
                    xml.endDocument();
                }
                if (application) {
                    System.out.println("Captura com " + count + " nós.");
                    return;
                }
            }
            throw new IllegalStateException("janela de aplicativo sem raiz acessível após dez consultas");
        } finally {
            UiAutomation.class.getMethod("disconnect").invoke(ui);
            thread.quitSafely();
        }
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
    private static void dump(XmlSerializer xml, AccessibilityNodeInfo node, int depth) throws Exception {
        if (depth > 80 || !node.isVisibleToUser()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.startTag("", "node");
        xml.attribute("", "text", text(node.getText()));
        xml.attribute("", "content-desc", text(node.getContentDescription()));
        xml.attribute("", "class", text(node.getClassName()));
        xml.attribute("", "package", text(node.getPackageName()));
        xml.attribute("", "bounds", "[" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]");
        xml.attribute("", "clickable", Boolean.toString(node.isClickable()));
        xml.attribute("", "scrollable", Boolean.toString(node.isScrollable()));
        count++;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) dump(xml, child, depth + 1);
        }
        xml.endTag("", "node");
    }
}
