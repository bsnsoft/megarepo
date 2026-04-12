package de.bsnsoft.megarepo.security.auth;

import de.bsnsoft.megarepo.core.security.UserStatus;
import de.bsnsoft.megarepo.database.entity.UserEntity;
import de.bsnsoft.megarepo.database.repository.UserJpaRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class MegaRepoUserDetailsService implements UserDetailsService {

    private final UserJpaRepository userJpaRepository;

    public MegaRepoUserDetailsService(UserJpaRepository userJpaRepository) {
        this.userJpaRepository = userJpaRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        UserEntity entity = userJpaRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        UserStatus status = UserStatus.valueOf(entity.getStatus());
        if (status != UserStatus.ACTIVE && status != UserStatus.CHANGE_PASSWORD) {
            throw new UsernameNotFoundException("User account is not active: " + userId);
        }

        var authorities = entity.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        return new User(
                entity.getUserId(),
                entity.getPasswordHash(),
                true,
                true,
                true,
                status != UserStatus.LOCKED,
                authorities);
    }
}
