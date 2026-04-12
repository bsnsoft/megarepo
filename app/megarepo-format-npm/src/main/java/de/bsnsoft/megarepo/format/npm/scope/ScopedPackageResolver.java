package de.bsnsoft.megarepo.format.npm.scope;

import org.springframework.stereotype.Component;

@Component
public class ScopedPackageResolver {

    public boolean isScoped(String name) {
        return name != null && name.startsWith("@") && name.contains("/");
    }

    public String getScope(String name) {
        if (!isScoped(name)) {
            throw new IllegalArgumentException("Not a scoped package: " + name);
        }
        int slashIndex = name.indexOf('/');
        return name.substring(0, slashIndex);
    }

    public String getPackageName(String name) {
        if (!isScoped(name)) {
            return name;
        }
        int slashIndex = name.indexOf('/');
        return name.substring(slashIndex + 1);
    }

    public String getFullName(String scope, String name) {
        if (scope == null || scope.isBlank()) {
            return name;
        }
        return scope + "/" + name;
    }
}
