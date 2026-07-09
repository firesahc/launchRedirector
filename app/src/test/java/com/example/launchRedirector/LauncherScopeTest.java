package com.example.launchRedirector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

public class LauncherScopeTest {

    @Test
    public void resourceScopeMatchesRuntimeScope() throws Exception {
        assertEquals(readXposedScopeResource(), LauncherScope.getPackages());
    }

    @Test
    public void scopeContainsConfiguredLauncher() {
        assertTrue(LauncherScope.contains("com.miui.home"));
    }

    private static List<String> readXposedScopeResource() throws Exception {
        File xml = new File("src/main/res/values/array.xml");
        if (!xml.exists()) {
            xml = new File("app/src/main/res/values/array.xml");
        }

        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(xml);
        NodeList arrays = document.getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            if (!"xposed_scope".equals(arrays.item(i).getAttributes()
                    .getNamedItem("name").getNodeValue())) {
                continue;
            }

            NodeList children = arrays.item(i).getChildNodes();
            List<String> result = new ArrayList<>();
            for (int j = 0; j < children.getLength(); j++) {
                if ("item".equals(children.item(j).getNodeName())) {
                    result.add(children.item(j).getTextContent());
                }
            }
            return result;
        }
        throw new IllegalStateException("xposed_scope resource not found");
    }
}
