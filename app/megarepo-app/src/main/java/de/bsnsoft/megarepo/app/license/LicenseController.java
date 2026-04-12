package de.bsnsoft.megarepo.app.license;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/system/license")
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping
    public ResponseEntity<LicenseStatusXO> getStatus() {
        var licensed = licenseService.isLicensed();
        var info = licenseService.getLicenseInfo().orElse(null);
        var activeUsers = licenseService.getActiveUserCount();
        var requiresPurchase = activeUsers > 50 && !licensed;

        String message;
        if (licensed && info != null) {
            message = "Licensed to " + info.company();
        } else if (requiresPurchase) {
            message = "MegaRepo Community Edition — " + activeUsers
                    + " active users detected (limit: 50). Purchase a business license at bsnsoft.de/megarepo";
        } else {
            message = "MegaRepo Community Edition";
        }

        return ResponseEntity.ok(new LicenseStatusXO(
                licensed,
                info != null ? info.company() : null,
                info != null ? info.email() : null,
                info != null ? info.issuedAt() : null,
                activeUsers,
                requiresPurchase,
                message));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LicenseStatusXO> uploadLicense(@RequestBody byte[] content) throws IOException {
        var license = licenseService.uploadLicense(content);
        var activeUsers = licenseService.getActiveUserCount();

        return ResponseEntity.ok(new LicenseStatusXO(
                true,
                license.company(),
                license.email(),
                license.issuedAt(),
                activeUsers,
                false,
                "Licensed to " + license.company()));
    }

    @DeleteMapping
    public ResponseEntity<Void> removeLicense() throws IOException {
        licenseService.removeLicense();
        return ResponseEntity.noContent().build();
    }
}
