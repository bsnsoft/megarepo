package de.bsnsoft.megarepo.format.maven.pom;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Optional;

@Component
public class PomParser {

    public record PomInfo(String groupId, String artifactId, String version, String packaging) {}

    public Optional<PomInfo> parsePom(InputStream pomContent) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            Document doc = builder.parse(pomContent);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            if (!"project".equals(root.getTagName())) {
                return Optional.empty();
            }

            String groupId = getDirectChildText(root, "groupId");
            String artifactId = getDirectChildText(root, "artifactId");
            String version = getDirectChildText(root, "version");
            String packaging = getDirectChildText(root, "packaging");

            // Inherit from parent if not set directly
            if (groupId == null || version == null) {
                NodeList parentNodes = root.getElementsByTagName("parent");
                if (parentNodes.getLength() > 0) {
                    Element parent = (Element) parentNodes.item(0);
                    if (groupId == null) {
                        groupId = getDirectChildText(parent, "groupId");
                    }
                    if (version == null) {
                        version = getDirectChildText(parent, "version");
                    }
                }
            }

            if (packaging == null) {
                packaging = "jar";
            }

            if (artifactId == null) {
                return Optional.empty();
            }

            return Optional.of(new PomInfo(groupId, artifactId, version, packaging));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String getDirectChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tagName.equals(element.getTagName())) {
                String text = element.getTextContent();
                return text != null && !text.isBlank() ? text.trim() : null;
            }
        }
        return null;
    }
}
