package com.gathin.tvhome;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every view id defined in the base activity_main.xml must exist in every layout-* variant of it.
 * MainActivity binds these ids with findViewById() and uses them immediately (clockSlot.post in
 * onCreate), so a variant that misses one is a NullPointerException on launch — layout-iw shipped
 * exactly that: a crash the moment the TV's language was Hebrew. This test turns such a gap into
 * a red build instead.
 */
public class LayoutParityTest {

    /** Locates app/src/main/res whether the cwd is the module dir (Gradle) or the repo root (direct JUnit). */
    private static File mainRes() {
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            File res = p.resolve("src/main/res").toFile();
            if (res.isDirectory()) return res;
            res = p.resolve("launcher/app/src/main/res").toFile();
            if (res.isDirectory()) return res;
        }
        throw new AssertionError("cannot locate launcher/app/src/main/res from "
                + Paths.get("").toAbsolutePath());
    }

    /** All ids a layout defines, written as @+id/name anywhere in the file. */
    private static TreeSet<String> ids(File layout) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout);
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            NamedNodeMap attrs = all.item(i).getAttributes();
            if (attrs == null) continue;
            Node id = attrs.getNamedItem("android:id");
            if (id != null) {
                String v = id.getNodeValue();
                if (v.startsWith("@+id/")) out.add(v.substring("@+id/".length()));
            }
        }
        return out;
    }

    @Test
    public void everyVariantOfActivityMainHasAllBaseIds() throws Exception {
        File res = mainRes();
        File base = new File(res, "layout/activity_main.xml");
        assertTrue("base layout missing: " + base, base.isFile());
        TreeSet<String> required = ids(base);
        assertFalse("base layout defines no ids", required.isEmpty());

        List<File> variants = new ArrayList<>();
        File[] dirs = res.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.getName().startsWith("layout-")) continue;
                File f = new File(d, "activity_main.xml");
                if (f.isFile()) variants.add(f);
            }
        }
        assertFalse("no layout-*/activity_main.xml variants found", variants.isEmpty());

        for (File v : variants) {
            TreeSet<String> missing = new TreeSet<>(required);
            missing.removeAll(ids(v));
            assertTrue(v.getParentFile().getName() + "/activity_main.xml misses ids the base "
                    + "layout defines (MainActivity uses them without null checks): " + missing,
                    missing.isEmpty());
        }
    }
}
