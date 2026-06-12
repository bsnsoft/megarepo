package de.bsnsoft.megarepo.format.nuget.meta;

import de.bsnsoft.megarepo.format.nuget.TestNupkgs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NupkgReaderTest {

    private final NupkgReader reader = new NupkgReader();

    @Test
    void read_extractsIdVersionDescriptionAuthors() throws IOException {
        byte[] nupkg = TestNupkgs.nupkg("My.Package", "1.2.3", "A test package");

        NupkgReader.NupkgContent content = reader.read(nupkg);

        assertEquals("My.Package", content.metadata().id());
        assertEquals("1.2.3", content.metadata().version());
        assertEquals("A test package", content.metadata().description());
        assertEquals("MegaRepo Tests", content.metadata().authors());
        assertTrue(new String(content.nuspecBytes(), StandardCharsets.UTF_8).contains("<id>My.Package</id>"),
                "raw nuspec bytes should be returned verbatim");
    }

    @Test
    void read_extractsGroupedDependencies() throws IOException {
        byte[] nupkg = TestNupkgs.nupkgWithNuspec(
                "deps.nuspec", TestNupkgs.nuspecWithDependencies("Deps.Package", "2.0.0"));

        NuspecMetadata metadata = reader.read(nupkg).metadata();

        assertEquals(3, metadata.dependencies().size());
        NuspecMetadata.Dependency first = metadata.dependencies().getFirst();
        assertEquals("Newtonsoft.Json", first.id());
        assertEquals("[13.0.3, )", first.versionRange());
        assertEquals("net8.0", first.targetFramework());
        assertEquals("netstandard2.0", metadata.dependencies().getLast().targetFramework());
    }

    @Test
    void read_extractsFlatDependencies() throws IOException {
        String nuspec = """
                <?xml version="1.0"?>
                <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
                  <metadata>
                    <id>Flat.Deps</id>
                    <version>1.0.0</version>
                    <dependencies>
                      <dependency id="SomeLib" version="1.0.0" />
                    </dependencies>
                  </metadata>
                </package>
                """;
        NuspecMetadata metadata = reader.read(TestNupkgs.nupkgWithNuspec("flat.nuspec", nuspec)).metadata();

        assertEquals(1, metadata.dependencies().size());
        assertEquals("SomeLib", metadata.dependencies().getFirst().id());
        assertEquals("", metadata.dependencies().getFirst().targetFramework());
    }

    @Test
    void read_worksWithoutXmlNamespace() throws IOException {
        String nuspec = """
                <?xml version="1.0"?>
                <package>
                  <metadata>
                    <id>NoNamespace</id>
                    <version>0.1.0</version>
                  </metadata>
                </package>
                """;
        NuspecMetadata metadata = reader.read(TestNupkgs.nupkgWithNuspec("n.nuspec", nuspec)).metadata();
        assertEquals("NoNamespace", metadata.id());
        assertEquals("0.1.0", metadata.version());
    }

    @Test
    void read_rejectsPackageWithoutNuspec() {
        byte[] zipWithoutNuspec = TestNupkgs.nupkgWithNuspec("README.md", "# not a nuspec");
        // entry is not *.nuspec — root-level nuspec is missing
        IOException ex = assertThrows(IOException.class, () -> reader.read(zipWithoutNuspec));
        assertTrue(ex.getMessage().contains("no root-level .nuspec"));
    }

    @Test
    void read_ignoresNuspecInSubdirectory() {
        byte[] nupkg = TestNupkgs.nupkgWithNuspec(
                "sub/dir/evil.nuspec", TestNupkgs.simpleNuspec("Evil", "1.0.0", "nested"));
        assertThrows(IOException.class, () -> reader.read(nupkg));
    }

    @Test
    void read_rejectsNonZipData() {
        byte[] garbage = "this is not a zip file".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> reader.read(garbage));
    }

    @Test
    void read_rejectsNuspecWithoutIdOrVersion() {
        String nuspec = """
                <?xml version="1.0"?>
                <package><metadata><id>OnlyId</id></metadata></package>
                """;
        assertThrows(IOException.class,
                () -> reader.read(TestNupkgs.nupkgWithNuspec("x.nuspec", nuspec)));
    }

    @Test
    void read_rejectsDoctypeDeclarations() {
        String nuspec = """
                <?xml version="1.0"?>
                <!DOCTYPE package [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <package><metadata><id>Xxe</id><version>1.0.0</version></metadata></package>
                """;
        assertThrows(IOException.class,
                () -> reader.read(TestNupkgs.nupkgWithNuspec("xxe.nuspec", nuspec)));
    }
}
