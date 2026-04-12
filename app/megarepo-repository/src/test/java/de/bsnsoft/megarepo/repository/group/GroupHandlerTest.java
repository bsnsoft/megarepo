package de.bsnsoft.megarepo.repository.group;

import de.bsnsoft.megarepo.core.format.FormatPlugin;
import de.bsnsoft.megarepo.core.format.FormatRegistry;
import de.bsnsoft.megarepo.core.format.FormatRequestHandler;
import de.bsnsoft.megarepo.core.format.FormatResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ContentResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.ErrorResponse;
import de.bsnsoft.megarepo.core.format.FormatResponse.NotFoundResponse;
import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryType;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupHandlerTest {

    @Mock
    private GroupMemberResolver groupMemberResolver;

    @Mock
    private FormatRegistry formatRegistry;

    @Mock
    private FormatPlugin formatPlugin;

    @Mock
    private FormatRequestHandler formatRequestHandler;

    @Mock
    private HttpServletRequest request;

    private GroupHandler groupHandler;

    private RepositoryConfig groupRepo;
    private RepositoryConfig hostedMember1;
    private RepositoryConfig hostedMember2;
    private RepositoryConfig proxyMember;

    @BeforeEach
    void setUp() {
        groupHandler = new GroupHandler(groupMemberResolver, formatRegistry);
        groupRepo = new RepositoryConfig(
                UUID.randomUUID(), "my-group", "maven2", RepositoryType.GROUP, true, "default", Map.of());
        hostedMember1 = new RepositoryConfig(
                UUID.randomUUID(), "hosted-1", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        hostedMember2 = new RepositoryConfig(
                UUID.randomUUID(), "hosted-2", "maven2", RepositoryType.HOSTED, true, "default", Map.of());
        proxyMember = new RepositoryConfig(
                UUID.randomUUID(), "proxy-central", "maven2", RepositoryType.PROXY, true, "default", Map.of());
    }

    @Test
    void handleGet_artifact_returnsFirstMatch() {
        byte[] data = "jar content".getBytes(StandardCharsets.UTF_8);
        var content = new ContentResponse(
                new ByteArrayInputStream(data), "application/java-archive", data.length, Map.of(), Map.of());

        when(groupMemberResolver.resolveMembers(groupRepo))
                .thenReturn(List.of(hostedMember1, hostedMember2));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.isMetadataPath("com/example/lib.jar")).thenReturn(false);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember1), eq("com/example/lib.jar"), eq(request)))
                .thenReturn(content);

        FormatResponse result = groupHandler.handleGet(groupRepo, "com/example/lib.jar", request);

        assertInstanceOf(ContentResponse.class, result);
        // Should not have queried member2 because member1 returned a hit
        verify(formatRequestHandler, never()).handleHostedGet(eq(hostedMember2), any(), any());
    }

    @Test
    void handleGet_artifact_skipsMember404_returnsFromLaterMember() {
        byte[] data = "jar content".getBytes(StandardCharsets.UTF_8);
        var notFound = new NotFoundResponse("Not found");
        var content = new ContentResponse(
                new ByteArrayInputStream(data), "application/java-archive", data.length, Map.of(), Map.of());

        when(groupMemberResolver.resolveMembers(groupRepo))
                .thenReturn(List.of(hostedMember1, proxyMember));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.isMetadataPath("com/example/lib.jar")).thenReturn(false);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember1), eq("com/example/lib.jar"), eq(request)))
                .thenReturn(notFound);
        when(formatRequestHandler.handleProxyGet(eq(proxyMember), eq("com/example/lib.jar"), eq(request)))
                .thenReturn(content);

        FormatResponse result = groupHandler.handleGet(groupRepo, "com/example/lib.jar", request);

        assertInstanceOf(ContentResponse.class, result);
    }

    @Test
    void handleGet_artifact_allMembers404_returnsNotFound() {
        var notFound1 = new NotFoundResponse("Not found in hosted-1");
        var notFound2 = new NotFoundResponse("Not found in hosted-2");

        when(groupMemberResolver.resolveMembers(groupRepo))
                .thenReturn(List.of(hostedMember1, hostedMember2));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.isMetadataPath("com/example/lib.jar")).thenReturn(false);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember1), eq("com/example/lib.jar"), eq(request)))
                .thenReturn(notFound1);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember2), eq("com/example/lib.jar"), eq(request)))
                .thenReturn(notFound2);

        FormatResponse result = groupHandler.handleGet(groupRepo, "com/example/lib.jar", request);

        assertInstanceOf(NotFoundResponse.class, result);
    }

    @Test
    void handleGet_metadata_mergesFromAllMembers() {
        byte[] merged = "<metadata>merged</metadata>".getBytes(StandardCharsets.UTF_8);
        var mergedResponse = new ContentResponse(
                new ByteArrayInputStream(merged), "application/xml", merged.length, Map.of(), Map.of());

        var content1 = new ContentResponse(
                new ByteArrayInputStream("m1".getBytes()), "application/xml", 2, Map.of(), Map.of());
        var content2 = new ContentResponse(
                new ByteArrayInputStream("m2".getBytes()), "application/xml", 2, Map.of(), Map.of());

        when(groupMemberResolver.resolveMembers(groupRepo))
                .thenReturn(List.of(hostedMember1, hostedMember2));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.isMetadataPath("com/example/maven-metadata.xml")).thenReturn(true);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember1), eq("com/example/maven-metadata.xml"), eq(request)))
                .thenReturn(content1);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember2), eq("com/example/maven-metadata.xml"), eq(request)))
                .thenReturn(content2);
        when(formatRequestHandler.mergeMetadata(eq(groupRepo), eq("com/example/maven-metadata.xml"), any()))
                .thenReturn(Optional.of(mergedResponse));

        FormatResponse result = groupHandler.handleGet(groupRepo, "com/example/maven-metadata.xml", request);

        assertInstanceOf(ContentResponse.class, result);
        // Verify both members were queried (metadata fetches from ALL)
        verify(formatRequestHandler).handleHostedGet(eq(hostedMember1), eq("com/example/maven-metadata.xml"), eq(request));
        verify(formatRequestHandler).handleHostedGet(eq(hostedMember2), eq("com/example/maven-metadata.xml"), eq(request));
        verify(formatRequestHandler).mergeMetadata(eq(groupRepo), eq("com/example/maven-metadata.xml"), any());
    }

    @Test
    void handleGet_metadata_mergeFails_returnsFallback() {
        var notFound = new NotFoundResponse("Not found");
        byte[] data = "raw metadata".getBytes(StandardCharsets.UTF_8);
        var content = new ContentResponse(
                new ByteArrayInputStream(data), "application/xml", data.length, Map.of(), Map.of());

        when(groupMemberResolver.resolveMembers(groupRepo))
                .thenReturn(List.of(hostedMember1, hostedMember2));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.isMetadataPath("com/example/maven-metadata.xml")).thenReturn(true);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember1), eq("com/example/maven-metadata.xml"), eq(request)))
                .thenReturn(notFound);
        when(formatRequestHandler.handleHostedGet(eq(hostedMember2), eq("com/example/maven-metadata.xml"), eq(request)))
                .thenReturn(content);
        when(formatRequestHandler.mergeMetadata(eq(groupRepo), eq("com/example/maven-metadata.xml"), any()))
                .thenReturn(Optional.empty());

        FormatResponse result = groupHandler.handleGet(groupRepo, "com/example/maven-metadata.xml", request);

        // Falls back to first non-404 response
        assertInstanceOf(ContentResponse.class, result);
    }

    @Test
    void handleGet_noOnlineMembers_returnsNotFound() {
        when(groupMemberResolver.resolveMembers(groupRepo))
                .thenReturn(List.of());

        FormatResponse result = groupHandler.handleGet(groupRepo, "com/example/lib.jar", request);

        assertInstanceOf(NotFoundResponse.class, result);
    }

    @Test
    void handlePut_delegatesToWritableMember() {
        var writableMember = hostedMember1;
        var created = new FormatResponse.CreatedResponse("/repository/hosted-1/com/lib.jar", Map.of());
        var attrs = Map.<String, Object>of("group", Map.of("writableMember", "hosted-1"));
        var groupWithWritable = new RepositoryConfig(
                groupRepo.id(), groupRepo.name(), "maven2", RepositoryType.GROUP, true, "default", attrs);

        when(groupMemberResolver.getWritableMember(groupWithWritable))
                .thenReturn(Optional.of(writableMember));
        when(formatRegistry.getPlugin("maven2")).thenReturn(formatPlugin);
        when(formatPlugin.getRequestHandler()).thenReturn(formatRequestHandler);
        when(formatRequestHandler.handleHostedPut(eq(writableMember), eq("com/lib.jar"), eq(request)))
                .thenReturn(created);

        FormatResponse result = groupHandler.handlePut(groupWithWritable, "com/lib.jar", request);

        assertInstanceOf(FormatResponse.CreatedResponse.class, result);
        verify(formatRequestHandler).handleHostedPut(writableMember, "com/lib.jar", request);
    }

    @Test
    void handlePut_noWritableMember_returns405() {
        when(groupMemberResolver.getWritableMember(groupRepo))
                .thenReturn(Optional.empty());

        FormatResponse result = groupHandler.handlePut(groupRepo, "com/lib.jar", request);

        assertInstanceOf(ErrorResponse.class, result);
        ErrorResponse error = (ErrorResponse) result;
        assertEquals(405, error.statusCode());
    }
}
