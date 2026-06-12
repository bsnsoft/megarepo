package de.bsnsoft.megarepo.format.nuget.meta;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a {@code .nupkg} (a ZIP archive) and extracts the {@code .nuspec}
 * manifest. Pure {@code java.util.zip} + JDK XML — no extra dependencies.
 */
@Component
public class NupkgReader {

    /** Maximum accepted size of the embedded .nuspec (decompression-bomb guard). */
    private static final int MAX_NUSPEC_SIZE = 4 * 1024 * 1024;

    /**
     * Result of reading a package: the parsed manifest plus the raw nuspec
     * bytes (served verbatim via the flat container).
     */
    public record NupkgContent(NuspecMetadata metadata, byte[] nuspecBytes) {}

    /**
     * Extracts and parses the root-level {@code .nuspec} from the package.
     *
     * @throws IOException if the file is not a valid ZIP, has no root-level
     *                     nuspec, or the manifest is not parseable
     */
    public NupkgContent read(byte[] nupkgData) throws IOException {
        byte[] nuspecBytes = extractNuspec(nupkgData);
        if (nuspecBytes == null) {
            throw new IOException("Package contains no root-level .nuspec manifest — not a valid .nupkg");
        }
        NuspecMetadata metadata = parseNuspec(nuspecBytes);
        return new NupkgContent(metadata, nuspecBytes);
    }

    private byte[] extractNuspec(byte[] nupkgData) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(nupkgData))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory()
                        && name.toLowerCase().endsWith(".nuspec")
                        && !name.contains("/")
                        && !name.contains("\\")) {
                    return readBounded(zip);
                }
            }
        }
        return null;
    }

    private byte[] readBounded(ZipInputStream zip) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > MAX_NUSPEC_SIZE) {
                throw new IOException("Embedded .nuspec exceeds maximum size");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    NuspecMetadata parseNuspec(byte[] nuspecBytes) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Secure processing: no DTDs, no external entities
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(nuspecBytes));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid .nuspec XML: " + e.getMessage(), e);
        }

        Element metadata = firstChildElement(doc.getDocumentElement(), "metadata");
        if (metadata == null) {
            throw new IOException(".nuspec has no <metadata> element");
        }

        String id = textOfChild(metadata, "id");
        String version = textOfChild(metadata, "version");
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
            throw new IOException(".nuspec must declare both <id> and <version>");
        }

        String description = textOfChild(metadata, "description");
        String authors = textOfChild(metadata, "authors");
        List<NuspecMetadata.Dependency> dependencies = parseDependencies(metadata);

        return new NuspecMetadata(id.trim(), version.trim(), description, authors, dependencies);
    }

    private List<NuspecMetadata.Dependency> parseDependencies(Element metadata) {
        List<NuspecMetadata.Dependency> result = new ArrayList<>();
        Element dependenciesEl = firstChildElement(metadata, "dependencies");
        if (dependenciesEl == null) {
            return result;
        }
        // Two shapes: flat <dependency> children, or <group targetFramework="..."> wrappers
        collectDependencies(dependenciesEl, "", result);
        NodeList children = dependenciesEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && "group".equals(el.getLocalName())) {
                String tfm = el.getAttribute("targetFramework");
                collectDependencies(el, tfm == null ? "" : tfm, result);
            }
        }
        return result;
    }

    private void collectDependencies(Element parent, String targetFramework, List<NuspecMetadata.Dependency> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && "dependency".equals(el.getLocalName())) {
                String depId = el.getAttribute("id");
                if (depId != null && !depId.isBlank()) {
                    result.add(new NuspecMetadata.Dependency(
                            depId, el.getAttribute("version"), targetFramework));
                }
            }
        }
    }

    private Element firstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private String textOfChild(Element parent, String localName) {
        Element child = firstChildElement(parent, localName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text != null && !text.isBlank() ? text.trim() : null;
    }
}
