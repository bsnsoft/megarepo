# Übergabe: Sofort widerrufbare Personal Access Tokens (PATs)

**Status:** geplant, noch nicht implementiert
**Quelle:** osTicket #117649 (eurodata) — Folge-Feature
**Erstellt:** 2026-06-15
**Für:** den Bot/Entwickler, der dieses Feature umsetzt

---

## 1. Problem (warum dieses Feature)

Heute ist der „NuGet API Key" identisch mit dem zustandslosen MegaRepo-**JWT**.
Über die Account-Seite gibt es seit Kurzem **„Reset API key"**
(`POST /api/v1/security/auth/regenerate-token`, siehe
[`AuthController.regenerateToken`](../app/megarepo-rest-api/src/main/java/de/bsnsoft/megarepo/rest/controller/AuthController.java)),
das ein **neues** JWT ausstellt.

**Aber:** Das ALTE JWT bleibt bis zum natürlichen Ablauf gültig
(`megarepo.security.jwt.access-token-expiry`, Default 12h), weil es **keine
serverseitige Revocation-Liste** gibt. Sofort-Sperrung geht heute nur über:

- Benutzer deaktivieren/sperren (sperrt den ganzen Account), oder
- `megarepo.security.jwt.secret` rotieren (sperrt **alle** Tokens **aller** User).

Dieser Caveat ist in [`docs/admin-guide.md`](admin-guide.md) (Abschnitt NuGet,
„Note on revocation", ca. Zeile 563) dokumentiert. **Dieses Feature löst genau
diese Einschränkung.**

## 2. Ziel

Echte, **sofort widerrufbare** Personal Access Tokens:

- Persistenter, **gehashter** Token-Store in der DB (Klartext-Token nie speichern).
- Lookup im Auth-Filter für `X-NuGet-ApiKey` **und** `Authorization: Bearer`.
- Lifecycle: **Erstellen** → **einmalig anzeigen** → **widerrufen** → **Ablauf**.
- Mehrere **benannte** Keys pro User, einzeln widerrufbar.
- UI auf der Account-Seite.
- Bestehendes **JWT-Login + Sliding-Session bleibt unangetastet** — PATs stehen
  sauber daneben.
- Tests grün + `docs/admin-guide.md` aktualisiert.

## 3. Rahmenbedingungen (Konventionen)

- **EGB-Branching** (siehe [`docs/EGB.md`](EGB.md)): Feature-Branch von der
  stabilsten Branch, auf der das Problem existiert (hier `main`), z.B.
  `feat/personal-access-tokens`. Bei Sprint-Start ggf. zuerst taggen.
  Version kommt aus `git describe --tags` — **keine Version in Dateien**.
- **Commit referenziert das Ticket:** Betreff im Stil der bisherigen Commits,
  Body/Footer mit `osTicket #117649`.
- **Tests müssen grün sein vor Merge.** Build: `./app/gradlew -p app build`
  (bzw. `test`).
- **Co-Author-Footer** in Commits nicht vergessen (siehe Repo-Konvention).
- Reply an den Kunden/osTicket-Handling übernimmt der Haupt-Loop nach Abschluss
  + Verify (siehe `claude-managed/CLAUDE.md`, osTicket-Routine + Clockodo).

---

## 4. Wie Auth heute funktioniert (relevante Stellen)

| Was | Datei |
|---|---|
| Filter, der Token aus Request zieht & SecurityContext setzt | [`JwtAuthenticationFilter`](../app/megarepo-security/src/main/java/de/bsnsoft/megarepo/security/auth/JwtAuthenticationFilter.java) |
| JWT erzeugen/validieren/Claims lesen | [`JwtTokenProvider`](../app/megarepo-security/src/main/java/de/bsnsoft/megarepo/security/auth/JwtTokenProvider.java) |
| Filter-Chain, permitAll/authenticated-Matcher | [`SecurityConfig`](../app/megarepo-security/src/main/java/de/bsnsoft/megarepo/security/SecurityConfig.java) |
| Login / refresh / regenerate-token | [`AuthController`](../app/megarepo-rest-api/src/main/java/de/bsnsoft/megarepo/rest/controller/AuthController.java) |
| User-Entity (PK = `user_id` = Username, Rollen in `user_roles`) | [`UserEntity`](../app/megarepo-database/src/main/java/de/bsnsoft/megarepo/database/entity/UserEntity.java) |
| User-Service | [`UserService`](../app/megarepo-security/src/main/java/de/bsnsoft/megarepo/security/service/UserService.java) |
| Account-UI (aktuelle „Reset API key"-Karte) | [`AccountPage.tsx`](../app/megarepo-web-ui/frontend/src/pages/account/AccountPage.tsx) |
| API-Client (Frontend) | [`frontend/src/api/client.ts`](../app/megarepo-web-ui/frontend/src/api/client.ts) |

**Token-Auflösung heute** (`JwtAuthenticationFilter.resolveToken`): in dieser
Reihenfolge `Authorization: Bearer <jwt>` → Header `X-NuGet-ApiKey: <jwt>` →
Cookie `access_token`. Bei gültigem JWT wird das Principal auf `userId` (=
Username) gesetzt, Authorities = `ROLE_<role>` aus dem `roles`-Claim.

**Wichtige Details, die die Umsetzung formen:**

- Principal = Username (String), nicht numerische ID.
- Rollen stehen heute im JWT-Claim. **Für PATs Rollen NICHT im Token einfrieren**
  → live aus dem User laden, damit Rollen-/Status-Änderungen sofort greifen
  (sonst hängt ein PAT an veralteten Rollen).
- `SecurityConfig` macht **`/api/v1/security/auth/**` = `permitAll`** (Zeile 72).
  Die Token-Verwaltungs-Endpunkte dürfen **NICHT** unter diesem Prefix liegen,
  sonst wären sie unauthentifiziert erreichbar. → Eigener Pfad unter
  `/api/v1/**` (fällt auf `authenticated()`), siehe §5.4.
- Session ist `STATELESS`. PAT-Auth passt da gut rein (jeder Request trägt das
  Token), Cookie/Sliding-Session der UI bleibt unberührt.

---

## 5. Umsetzungsvorschlag

### 5.1 DB-Migration `V11__personal_access_tokens.sql`

Flyway, nächste freie Nummer ist **V11** (aktuell höchste:
`V10__outbound_proxy_settings.sql`). Verzeichnis:
`app/megarepo-database/src/main/resources/db/migration/`.

```sql
-- Persistent, individually revocable personal access tokens (PATs).
-- Replaces the "reset issues a new JWT but the old one stays valid" caveat:
-- a PAT can be revoked server-side and stops working on the next request.
--
-- Only a SHA-256 hash of the token is stored, never the cleartext — the
-- secret is shown to the user exactly once at creation time (GitHub-style).
CREATE TABLE personal_access_tokens (
    id            UUID PRIMARY KEY,
    user_id       VARCHAR(200) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    name          VARCHAR(200) NOT NULL,        -- user-chosen label, e.g. "CI deploy"
    token_hash    VARCHAR(64)  NOT NULL UNIQUE, -- SHA-256 hex of the cleartext token
    token_prefix  VARCHAR(20)  NOT NULL,        -- e.g. "mrp_AbCd" for UI identification
    created_at    TIMESTAMPTZ  NOT NULL,
    last_used_at  TIMESTAMPTZ,                  -- updated on successful auth (best effort)
    expires_at    TIMESTAMPTZ,                  -- NULL = never expires
    revoked_at    TIMESTAMPTZ                   -- NULL = active
);

CREATE INDEX idx_pat_token_hash ON personal_access_tokens (token_hash);
CREATE INDEX idx_pat_user_id    ON personal_access_tokens (user_id);
```

> Hinweis: Andere Entities nutzen `@GeneratedValue(strategy = AUTO)` für UUIDs
> (siehe `SslCertificateEntity`). Entweder denselben Stil verwenden (dann kein
> explizites `id` beim Insert) oder die UUID im Service erzeugen. Konsistent mit
> bestehendem Code bleiben.

### 5.2 Entity + Repository

`PersonalAccessTokenEntity` in
`app/megarepo-database/src/main/java/de/bsnsoft/megarepo/database/entity/`
(Vorlage: `SslCertificateEntity` — UUID-PK, `Instant`-Felder).

`PersonalAccessTokenJpaRepository` in
`app/megarepo-database/.../repository/` (Vorlage:
`SslCertificateJpaRepository`):

```java
public interface PersonalAccessTokenJpaRepository
        extends JpaRepository<PersonalAccessTokenEntity, UUID> {
    Optional<PersonalAccessTokenEntity> findByTokenHash(String tokenHash);
    List<PersonalAccessTokenEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<PersonalAccessTokenEntity> findByIdAndUserId(UUID id, String userId);
}
```

### 5.3 Service: `PersonalAccessTokenService`

In `app/megarepo-security/.../service/` (neben `UserService`).

Verantwortlich für:

- **`create(userId, name, expiresInDays?)`** → erzeugt Klartext-Token,
  speichert nur Hash, gibt das **Klartext-Token genau einmal** zurück
  (z.B. als `CreatedToken(entity, cleartext)`-Record).
- **`list(userId)`** → Metadaten (nie das Secret).
- **`revoke(userId, tokenId)`** → setzt `revoked_at` (idempotent; nur eigene
  Tokens — `findByIdAndUserId`).
- **`authenticate(rawToken)`** → Kern für den Filter:
  1. Token-Format prüfen (Prefix `mrp_`), sonst „kein PAT" signalisieren.
  2. SHA-256 hashen, `findByTokenHash`.
  3. Prüfen: nicht `revoked_at`, nicht abgelaufen (`expires_at`).
  4. User laden, **Status == ACTIVE** prüfen, **Live-Rollen** holen.
  5. `last_used_at` aktualisieren (best effort, darf den Request nicht blocken).
  6. Authentication-Objekt zurückgeben (Principal = userId, Authorities =
     `ROLE_<role>`), analog zum heutigen JWT-Pfad.

**Token-Erzeugung & Hashing:**

- Format: `mrp_` + ~32 Byte Zufall, URL-safe Base64 ohne Padding
  (`SecureRandom`). Ergebnis ist hochentropisch.
- Hash: **SHA-256** über den Klartext, hex-codiert (64 Zeichen → passt zu
  `token_hash VARCHAR(64)`).
- **Nicht bcrypt:** bcrypt ist absichtlich langsam und für Passwörter gedacht;
  bei einem hochentropischen Token pro Request wäre das unnötig teuer und
  bräuchte einen Scan statt eines indizierten Lookups. SHA-256 ist hier der
  Standard (GitHub/GitLab machen es genauso).
- `token_prefix` = z.B. die ersten 8–12 Zeichen (`mrp_AbCd…`) für die
  Wiedererkennung in der Liste.

> Secret-Konvention im Projekt ist sonst „Klartext-Spalte, write-only, in UI
> maskiert" (siehe Kommentar in `V10`). **Für PATs bewusst abweichen** und
> hashen — sie müssen nie wieder angezeigt werden, und gehasht ist strikt
> sicherer. Diese Abweichung im Migrations-Kommentar begründen (oben schon
> getan).

### 5.4 Filter-Integration

**Empfehlung:** Den bestehenden `JwtAuthenticationFilter` erweitern, statt einen
zweiten Filter zu bauen — beide Token-Arten kommen über dieselben Header
(`Authorization: Bearer`, `X-NuGet-ApiKey`). So bleibt die Auflösungs-Reihenfolge
an einer Stelle.

Logik in `doFilterInternal`:

1. Rohes Token wie heute auflösen (`resolveToken`).
2. **Erst PAT versuchen**, wenn das Token mit `mrp_` beginnt:
   `patService.authenticate(raw)` → bei Erfolg SecurityContext setzen, fertig.
3. Sonst wie bisher als JWT behandeln (`jwtTokenProvider.validateToken`).

Vorteil des Prefix-Checks: kein DB-Lookup für normale JWTs (UI-Cookie-Requests),
und keine Verwechslung. JWTs haben das Prefix nicht.

> Falls ein **separater `PatAuthenticationFilter`** bevorzugt wird: in
> `SecurityConfig` **vor** `jwtFilter` einhängen
> (`addFilterBefore(patFilter, …)`), und beide müssen tolerant sein (kein Treffer
> → Request unverändert weiterreichen, damit der jeweils andere Filter greift).
> Das ist mehr Code für denselben Effekt — die Filter-Erweiterung ist einfacher.

`megarepo-security` hat schon Zugriff auf `megarepo-database` (UserJpaRepository
wird dort genutzt) — die PAT-Repository-Abhängigkeit passt also ins selbe Modul.

### 5.5 REST-API: Token-Verwaltung

Neuer Controller, z.B. `PersonalAccessTokenController` in
`app/megarepo-rest-api/.../controller/`.

**Pfad bewusst NICHT unter `/api/v1/security/auth/**`** (das ist `permitAll`!).
Stattdessen unter `/api/v1/**` (→ `authenticated`), Vorschlag:
`/api/v1/account/tokens` oder `/api/v1/security/users/me/tokens`
(`/security/users/me` existiert bereits für das Profil — konsistent).

| Methode | Pfad | Zweck | Response |
|---|---|---|---|
| `GET` | `…/tokens` | eigene Tokens auflisten | Metadaten (id, name, prefix, created/lastUsed/expires/revoked) — **nie das Secret** |
| `POST` | `…/tokens` | Token erstellen `{name, expiresInDays?}` | **einmalig** das Klartext-Token + Metadaten |
| `DELETE` | `…/tokens/{id}` | Token widerrufen | 204 |

`userId` immer aus `SecurityContextHolder` (wie `regenerateToken` heute), **nie**
aus dem Request-Body — ein User darf nur eigene Tokens sehen/widerrufen.

DTOs unter `app/megarepo-rest-api/.../dto/security/` (Vorlage: `TokenResponse`,
`ApiUser`). Validierung mit `@Valid`/Jakarta-Annotationen wie bei `LoginRequest`.

### 5.6 Frontend (Account-Seite)

Tech: **React + Vite + pnpm**, Source liegt in
`app/megarepo-web-ui/frontend/`. Build erzeugt das Static-Bundle, das Gradle ins
JAR packt (siehe `app/megarepo-web-ui/build.gradle.kts`). Der Ordner
`src/main/resources/static/assets/` ist Build-Output — **nicht** dort editieren.

Umbau der „API Key"-Karte in [`AccountPage.tsx`](../app/megarepo-web-ui/frontend/src/pages/account/AccountPage.tsx):

- Statt „eine Reset-Funktion fürs JWT" → **Liste benannter Tokens** mit
  Spalten: Name, Prefix, erstellt, zuletzt genutzt, Ablauf, Status; pro Zeile
  **Widerrufen**-Button (mit `ConfirmDialog`).
- **„Token erstellen"**: Name + optional Ablauf → Token wird **einmalig** in
  einem Dialog gezeigt (Copy-Button, deutlicher „wird nur einmal angezeigt"-
  Hinweis). Vorhandene `Toast`-, `ConfirmDialog`-, `DataTable`-Komponenten nutzen.
- API-Aufrufe über `api.get/post/del` aus `src/api/client.ts`.
- **Den amber „old key keeps working until it expires"-Hinweis entfernen** —
  er ist mit echtem Revoke nicht mehr wahr.

**Designentscheidung (siehe §7):** Soll die „API Key = mein Login-JWT"-Anzeige
ganz verschwinden und durch PATs ersetzt werden, oder beides nebeneinander?
Empfehlung: PATs werden der empfohlene Weg für Tooling; das aktuelle „aktuelles
JWT anzeigen" kann bleiben, sollte aber klar als „Session-Token" vom „Personal
Access Token" abgegrenzt werden. Vor der UI-Arbeit mit Christian klären.

### 5.7 `regenerate-token` / alter Pfad

Nicht löschen, solange die UI ihn nutzt — sonst entscheiden, ob er durch PATs
ersetzt wird. Mindestens den irreführenden Caveat-Text in der UI entfernen,
sobald PATs live sind. Mit Christian abstimmen (§7).

---

## 6. Tests

Bestehende Muster:

- **Service-Test** (JUnit, Mockito) wie `UserServiceTest`:
  Hashing (gleicher Input → gleicher Hash, Klartext nie persistiert),
  Erstellung gibt Klartext genau einmal, Ablauf/Revoke führen zu „nicht
  authentifizierbar", Live-Rollen werden geladen, fremde Token-IDs nicht
  widerrufbar.
- **Controller-Test** (Standalone-MockMvc) wie
  [`AuthControllerRegenerateTokenTest`](../app/megarepo-rest-api/src/test/java/de/bsnsoft/megarepo/rest/controller/AuthControllerRegenerateTokenTest.java):
  Create/List/Revoke, 401 ohne Auth, 403/404 bei fremdem Token.
- **Filter-Test**: `X-NuGet-ApiKey`/`Bearer` mit gültigem PAT → authentifiziert;
  widerrufenes/abgelaufenes PAT → nicht authentifiziert; JWT-Pfad bleibt
  unverändert.
- **Integrationstest** in `app/megarepo-integration-tests/` (vgl. NuGet-E2E aus
  Commit `c473a07`): `dotnet nuget push` mit PAT → ok, dann PAT widerrufen →
  nächster Push **401**. Das ist der eigentliche Beweis, dass das Feature den
  Caveat löst.

## 7. Offene Entscheidungen für Christian

1. **Rollen/Scopes:** PAT erbt alle Live-Rollen des Users (einfach, deckt den
   NuGet-Use-Case) — oder feinere Scopes (read/write, pro Repo)? Empfehlung:
   Rollen erben jetzt, Scopes als späteres Feature.
2. **Default-Ablauf:** kein Ablauf, 90 Tage, oder Pflichtfeld? Empfehlung:
   optional, Default „kein Ablauf", UI bietet Presets (30/90/365 Tage / nie).
3. **UI:** aktuelle „JWT als API-Key anzeigen"-Karte ersetzen oder neben PATs
   behalten? (§5.6)
4. **`regenerate-token`-Endpunkt** nach PAT-Einführung deprecaten/entfernen?
5. **Audit:** PAT-Erstellung/-Widerruf ins Audit-Log (`V4__audit_log.sql`
   existiert) aufnehmen? Empfehlung: ja, sicherheitsrelevant.

## 8. Doku-Updates am Ende

- [`docs/admin-guide.md`](admin-guide.md), NuGet-Abschnitt (~Z. 540–569):
  „Note on revocation" umschreiben — der Caveat ist gelöst. PAT-Workflow
  (Erstellen/Anzeigen/Widerrufen) dokumentieren.
- `CHANGELOG.md`: Feature-Eintrag.

## 9. Definition of Done

- [ ] V11-Migration + Entity + Repository
- [ ] `PersonalAccessTokenService` (create/list/revoke/authenticate, SHA-256, Live-Rollen, Status-Check)
- [ ] Filter-Integration (PAT vor JWT, Prefix-Check)
- [ ] REST-Controller unter authentifiziertem Pfad (nicht `/auth/**`)
- [ ] Account-UI: benannte Tokens, einmalige Anzeige, Widerruf
- [ ] Tests grün (Service, Controller, Filter, Integration: push→revoke→401)
- [ ] `admin-guide.md` + `CHANGELOG.md` aktualisiert
- [ ] JWT-Login/Sliding-Session nachweislich unverändert
- [ ] Selbst verifiziert (push mit PAT, dann revoke → 401), dann osTicket-Reply + Clockodo
