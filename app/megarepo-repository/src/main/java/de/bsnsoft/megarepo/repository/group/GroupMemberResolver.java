package de.bsnsoft.megarepo.repository.group;

import de.bsnsoft.megarepo.core.repository.RepositoryConfig;
import de.bsnsoft.megarepo.core.repository.RepositoryConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GroupMemberResolver {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberResolver.class);

    private final RepositoryConfigService repositoryConfigService;

    public GroupMemberResolver(RepositoryConfigService repositoryConfigService) {
        this.repositoryConfigService = repositoryConfigService;
    }

    /**
     * Resolves the ordered list of member repositories for a group, skipping offline members.
     */
    public List<RepositoryConfig> resolveMembers(RepositoryConfig groupRepo) {
        List<RepositoryConfig> members = repositoryConfigService.getGroupMembers(groupRepo.id());
        return members.stream()
                .filter(member -> {
                    if (!member.online()) {
                        log.debug("Skipping offline member '{}' in group '{}'", member.name(), groupRepo.name());
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    /**
     * Gets the writable member for a group repository. The writable member is specified
     * in the group attributes as {"group": {"writableMember": "member-name"}}.
     */
    @SuppressWarnings("unchecked")
    public Optional<RepositoryConfig> getWritableMember(RepositoryConfig groupRepo) {
        Object groupObj = groupRepo.attributes().get("group");
        if (groupObj instanceof Map<?, ?> groupAttrs) {
            Object writableMemberName = groupAttrs.get("writableMember");
            if (writableMemberName instanceof String name && !name.isBlank()) {
                return repositoryConfigService.getRepository(name);
            }
        }
        return Optional.empty();
    }
}
