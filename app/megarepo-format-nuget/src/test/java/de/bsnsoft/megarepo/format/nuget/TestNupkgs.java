package de.bsnsoft.megarepo.format.nuget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds real {@code .nupkg} ZIP fixtures for tests — no canned binaries. */
public final class TestNupkgs {

    private TestNupkgs() {}

    public static String simpleNuspec(String id, String version, String description) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
                  <metadata>
                    <id>%s</id>
                    <version>%s</version>
                    <authors>MegaRepo Tests</authors>
                    <description>%s</description>
                  </metadata>
                </package>
                """.formatted(id, version, description);
    }

    public static String nuspecWithDependencies(String id, String version) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
                  <metadata>
                    <id>%s</id>
                    <version>%s</version>
                    <authors>MegaRepo Tests</authors>
                    <description>With dependencies</description>
                    <dependencies>
                      <group targetFramework="net8.0">
                        <dependency id="Newtonsoft.Json" version="[13.0.3, )" />
                        <dependency id="Serilog" version="3.1.1" />
                      </group>
                      <group targetFramework="netstandard2.0">
                        <dependency id="Newtonsoft.Json" version="13.0.1" />
                      </group>
                    </dependencies>
                  </metadata>
                </package>
                """.formatted(id, version);
    }

    /** Builds a minimal valid .nupkg containing the given nuspec at the ZIP root. */
    public static byte[] nupkg(String id, String version, String description) {
        return nupkgWithNuspec(id + ".nuspec", simpleNuspec(id, version, description));
    }

    public static byte[] nupkgWithNuspec(String nuspecEntryName, String nuspecXml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("<Relationships/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(nuspecEntryName));
            zip.write(nuspecXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("lib/net8.0/dummy.dll"));
            zip.write(new byte[] {0x4d, 0x5a, 0x00, 0x01});
            zip.closeEntry();

            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
