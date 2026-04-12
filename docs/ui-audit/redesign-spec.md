# MegaRepo UI Redesign Specification

**Date:** 2026-03-28
**Author:** UX/UI Audit
**Status:** Ready for implementation

---

## Executive Summary

The current MegaRepo UI is functional but looks like an internal tool prototype, not a commercial product at EUR 600/year. The problems are systemic: inconsistent spacing, no visual hierarchy, unstyled form inputs, garish action buttons, and empty states that feel broken rather than helpful. The good news is that the layout structure (sidebar + content area) is solid and the navigation information architecture is correct. This is a styling and polish problem, not a structural one.

The redesign below can be implemented incrementally with Tailwind CSS classes alone -- no new libraries needed.

---

## 1. Design System

### 1.1 Color Palette

#### Primary Brand
| Token | Hex | Usage |
|-------|-----|-------|
| `brand-900` | `#0F172A` | Sidebar background (keep current) |
| `brand-800` | `#1E293B` | Sidebar hover, active states |
| `brand-700` | `#334155` | Sidebar section headers |
| `brand-600` | `#475569` | Secondary text on dark bg |
| `brand-50`  | `#F8FAFC` | Main content background |

#### Accent (Primary Action)
| Token | Hex | Usage |
|-------|-----|-------|
| `accent-600` | `#2563EB` | Primary buttons, active links |
| `accent-700` | `#1D4ED8` | Primary button hover |
| `accent-50`  | `#EFF6FF` | Light accent background (active sidebar item) |

#### Neutrals (Content Area)
| Token | Hex | Usage |
|-------|-----|-------|
| `gray-950` | `#030712` | Page titles (h1) |
| `gray-700` | `#374151` | Body text, table cell text |
| `gray-500` | `#6B7280` | Subtitle text, descriptions, placeholders |
| `gray-400` | `#9CA3AF` | Disabled text, sort arrows |
| `gray-200` | `#E5E7EB` | Borders, table row dividers |
| `gray-100` | `#F3F4F6` | Table header background, card background |
| `gray-50`  | `#F9FAFB` | Alternating table rows, page background |
| `white`    | `#FFFFFF` | Cards, content panels |

#### Semantic
| Token | Hex | Usage |
|-------|-----|-------|
| `success-600` | `#059669` | Online/healthy status, active badges |
| `success-50`  | `#ECFDF5` | Success badge background |
| `warning-600` | `#D97706` | Warning states, CHANGE_PASSWORD status |
| `warning-50`  | `#FFFBEB` | Warning badge background |
| `error-600`   | `#DC2626` | Error states, destructive buttons (text) |
| `error-50`    | `#FEF2F2` | Error badge background |
| `info-600`    | `#2563EB` | Info badges, links |
| `info-50`     | `#EFF6FF` | Info badge background |

### 1.2 Typography

**Font Family:** `Inter, system-ui, -apple-system, sans-serif`

Load Inter from Google Fonts (weight 400, 500, 600, 700). It is the industry standard for dashboards (used by Vercel, Linear, GitHub). If font loading is a concern, `system-ui` fallback looks nearly identical on macOS/Windows.

| Element | Size | Weight | Color | Tailwind Class |
|---------|------|--------|-------|----------------|
| Page title (h1) | 24px / 1.5rem | 600 (semibold) | `gray-950` | `text-2xl font-semibold text-gray-950` |
| Page subtitle | 14px / 0.875rem | 400 (normal) | `gray-500` | `text-sm text-gray-500` |
| Section heading | 16px / 1rem | 600 (semibold) | `gray-900` | `text-base font-semibold text-gray-900` |
| Table header | 12px / 0.75rem | 600 (semibold) | `gray-500` | `text-xs font-semibold text-gray-500 uppercase tracking-wider` |
| Table cell | 14px / 0.875rem | 400 (normal) | `gray-700` | `text-sm text-gray-700` |
| Body text | 14px / 0.875rem | 400 (normal) | `gray-700` | `text-sm text-gray-700` |
| Small / caption | 12px / 0.75rem | 400 (normal) | `gray-500` | `text-xs text-gray-500` |
| Sidebar nav item | 14px / 0.875rem | 500 (medium) | `gray-300` | `text-sm font-medium text-gray-300` |
| Sidebar section label | 11px / 0.6875rem | 600 (semibold) | `gray-500` | `text-[11px] font-semibold text-gray-500 uppercase tracking-widest` |

### 1.3 Spacing Scale

Use Tailwind's default 4px base. Key spacings used throughout:

| Token | Value | Usage |
|-------|-------|-------|
| `space-1` | 4px | Inline icon gap |
| `space-2` | 8px | Tight element gaps (badge padding, icon-to-text) |
| `space-3` | 12px | Form field internal padding |
| `space-4` | 16px | Standard padding, gap between form fields |
| `space-5` | 20px | Card padding |
| `space-6` | 24px | Section spacing, content area padding |
| `space-8` | 32px | Between page sections |
| `space-10` | 40px | Page top/bottom margin |

**Content area padding:** `px-8 py-6` (32px horizontal, 24px vertical)

### 1.4 Border Radius Scale

| Token | Value | Usage |
|-------|-------|-------|
| `rounded-sm` | 4px | Badges, small chips |
| `rounded-md` | 6px | Buttons, inputs, dropdowns |
| `rounded-lg` | 8px | Cards, modals, panels |
| `rounded-xl` | 12px | Dashboard stat cards |
| `rounded-full` | 9999px | Avatars, status dots |

### 1.5 Shadow Scale

| Token | Value | Usage |
|-------|-------|-------|
| `shadow-xs` | `0 1px 2px rgba(0,0,0,0.05)` | Inputs on focus |
| `shadow-sm` | `0 1px 3px rgba(0,0,0,0.1), 0 1px 2px rgba(0,0,0,0.06)` | Cards, panels |
| `shadow-md` | `0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06)` | Dropdowns, modals |
| `shadow-lg` | `0 10px 15px rgba(0,0,0,0.1), 0 4px 6px rgba(0,0,0,0.05)` | Modal overlays |

### 1.6 Component Specifications

#### Buttons

**Primary Button:**
```
bg-accent-600 hover:bg-accent-700 text-white
px-4 py-2 text-sm font-medium rounded-md
transition-colors duration-150
focus:outline-none focus:ring-2 focus:ring-accent-600 focus:ring-offset-2
```

**Secondary Button (Ghost):**
```
bg-white border border-gray-200 hover:bg-gray-50 text-gray-700
px-4 py-2 text-sm font-medium rounded-md
```

**Destructive Button:**
```
bg-white border border-gray-200 hover:bg-error-50 text-error-600
px-4 py-2 text-sm font-medium rounded-md
```
Never use a solid red background for delete buttons. Red text + border is sufficient and less alarming.

**Button Sizing:**
- Small: `px-3 py-1.5 text-xs`
- Default: `px-4 py-2 text-sm`
- Large: `px-6 py-2.5 text-base`

#### Form Inputs

```
w-full px-3 py-2 text-sm text-gray-700
border border-gray-200 rounded-md
bg-white
placeholder:text-gray-400
focus:border-accent-600 focus:ring-1 focus:ring-accent-600 focus:outline-none
transition-colors duration-150
```

**Label:**
```
block text-sm font-medium text-gray-700 mb-1.5
```

**Help text:**
```
text-xs text-gray-500 mt-1
```

Every input MUST have a visible border. The current underline-only style (Material Design holdover) looks unfinished.

#### Tables

**Table container:**
```
bg-white rounded-lg border border-gray-200 overflow-hidden
```

**Table header row:**
```
bg-gray-50 border-b border-gray-200
```

**Table header cell:**
```
px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider
```

**Table body row:**
```
border-b border-gray-100 hover:bg-gray-50 transition-colors
```

**Table body cell:**
```
px-4 py-3 text-sm text-gray-700
```

Sort indicators: use a small chevron icon (not the current triangle character), colored `gray-400` when inactive, `gray-700` when active.

#### Cards (Dashboard Stats)

```
bg-white rounded-xl border border-gray-200 p-5
flex items-center gap-4
```

Stat icon: 40x40px rounded-lg background with semantic color at 10% opacity, icon at full semantic color.
Stat value: `text-2xl font-semibold text-gray-950`
Stat label: `text-sm text-gray-500`

#### Badges / Status Indicators

**Status badge (pill):**
```
inline-flex items-center px-2.5 py-0.5
text-xs font-medium rounded-full
```

Variants:
- Active/Online: `bg-success-50 text-success-600`
- Warning: `bg-warning-50 text-warning-600`
- Error/Offline: `bg-error-50 text-error-600`
- Info/Default: `bg-info-50 text-info-600`
- Neutral: `bg-gray-100 text-gray-600`

**Format badge (for repository type):**
```
inline-flex items-center px-2 py-0.5
text-xs font-medium rounded-sm
bg-gray-100 text-gray-700
```

#### Empty States

```
flex flex-col items-center justify-center py-16 text-center
```

Icon: 48x48px, `text-gray-300` (use a proper SVG icon, not a rendered image/emoji)
Title: `text-base font-medium text-gray-900 mt-4`
Description: `text-sm text-gray-500 mt-1 max-w-sm`
Action button: Primary button, `mt-4`

#### Modals / Dialogs

```
fixed inset-0 z-50 flex items-center justify-center
bg-black/50 backdrop-blur-sm (overlay)

max-w-lg w-full bg-white rounded-lg shadow-lg p-6 (panel)
```

#### Sidebar Active State

Current active item: use a left 3px border accent + slightly lighter background
```
border-l-3 border-accent-600 bg-brand-800 text-white
```

Inactive item:
```
text-gray-400 hover:text-gray-200 hover:bg-brand-800
```

---

## 2. Per-Page Redesign Notes

### Page 01: Login

**What's wrong:**
- Card has no box shadow and blends into the dark background awkwardly
- Inputs use underline-only style (Material Design) -- looks unfinished and inconsistent with the rest of the app which uses bordered inputs
- The "MegaRepo" logo text uses orange for "Repo" which is a jarring two-tone brand treatment
- "by BSNSoft Solutions GmbH" subtitle is low-contrast gray-on-white
- No padding/spacing between label and input -- labels sit directly on top of the input line
- Sign In button is full-width which is fine, but has no border-radius -- looks flat

**Redesign spec:**
- Card: `max-w-sm mx-auto bg-white rounded-xl shadow-lg p-8`
- Add `space-y-5` between form groups
- Inputs: full bordered style (see component spec), height 40px
- Logo: single color treatment. Use white "MegaRepo" text on dark bg, or `gray-950` on the card. Drop the two-tone.
- Subtitle "Artifact Repository Manager": `text-sm text-gray-500 mt-1`
- Remove "by BSNSoft Solutions GmbH" from login -- it clutters. Put it in footer if needed.
- Sign In button: `w-full rounded-md` with standard primary button styling
- Add `mt-6` before the Sign In button for visual separation
- Background: keep the dark `brand-900` background, it is fine

### Page 02: Dashboard

**What's wrong:**
- Stat cards have inconsistent icon styles -- some use colored squares, one uses a checkmark circle. The icons appear to be low-resolution or rendered emoji.
- "Healthy" card breaks the pattern by being text-heavy while others are number-focused
- "Quick Actions" cards use a chevron `>` that looks like plain text
- "Quick Actions" section heading has no visual weight
- The overall page feels cramped in the small screenshot -- stat cards are too wide and take full width
- No visual separation between stats and quick actions

**Redesign spec:**
- Stat cards: use a 3-column grid with `gap-4`. Each card per the Card component spec.
- Replace emoji/image icons with monochrome SVG icons inside a 40x40 colored circle:
  - Repositories: blue circle, archive/box icon
  - Components: amber circle, package icon
  - Storage: purple circle, hard-drive icon
  - Health: green circle, heart/pulse icon
- "Healthy" card: show "Healthy" as the big stat value, "System Health" as the label. Same layout as others.
- Quick Actions: `mt-8`. Use a simple list with `divide-y divide-gray-100`, each item `py-3 flex items-center justify-between`. Left: icon + text stack. Right: chevron-right SVG icon (`text-gray-400`).
- Quick Actions heading: use Section heading style (`text-base font-semibold text-gray-900`)

### Page 03: Browse

**What's wrong:**
- Empty state uses a rendered folder image (looks like a Windows XP icon) -- visually dated
- "Create Repository" link is styled as a plain text link with a dotted underline -- unclear it is a button
- Filter input has no visible border, just an underline
- Two separate CTAs: "Create a repository to get started" (left-aligned text) and "Create Repository" (centered link). Confusing dual calls to action.

**Redesign spec:**
- Empty state: center everything. Use a single SVG folder-open icon (48px, `text-gray-300`).
- Single CTA: "No repositories found" as title. "Create a repository to start browsing artifacts." as description. Primary button "Create Repository" below.
- Remove the left-aligned "Create a repository to get started" text.
- Filter input: full bordered style per component spec.

### Page 04: Search

**What's wrong:**
- Tab navigation ("Keyword", "Maven", "npm", "PyPI") has no visible styling -- the tabs look like concatenated plain text with no spacing: "KeywordMavennpmPyPI" appears as a single run-on string
- Search input has no visible border
- Search button is a small blue pill pushed to the far right, detached from the input

**Redesign spec:**
- Tabs: use proper tab styling.
  ```
  flex border-b border-gray-200 gap-0
  ```
  Each tab:
  ```
  px-4 py-2.5 text-sm font-medium
  text-gray-500 hover:text-gray-700
  border-b-2 border-transparent hover:border-gray-300
  ```
  Active tab:
  ```
  text-accent-600 border-b-2 border-accent-600
  ```
- Search bar: combine input + button into a single visual group using `flex`:
  ```
  <div class="flex">
    <input class="flex-1 rounded-l-md border border-r-0 ..." />
    <button class="rounded-r-md border border-l-0 bg-accent-600 text-white px-4">Search</button>
  </div>
  ```
- Add `gap-6` between the tab bar and the search bar

### Page 05: Upload

**What's wrong:**
- Empty state uses a gray up-arrow icon that looks like a system glyph, not a designed icon
- No action button -- just text saying "Create a hosted repository to upload artifacts." There is nothing to click.
- Large amount of dead whitespace

**Redesign spec:**
- Empty state per the standard component spec
- Icon: upload-cloud SVG, 48px, `text-gray-300`
- Title: "No Hosted Repositories"
- Description: "Create a hosted repository to start uploading artifacts."
- Primary button: "Create Repository" (links to repo creation page)

### Page 06: Repositories (Admin)

**What's wrong:**
- Table headers use yellow/gold sort arrows -- clashes with the otherwise blue/gray palette
- "+ Create Repository" button is a small blue pill pushed to the far right with no padding consistency
- "0 repositories configured" subtitle has no visual weight
- Empty row text "No repositories configured yet" is italic -- unnecessary styling choice

**Redesign spec:**
- Table per the standard table component spec
- Sort arrows: use `text-gray-400` chevrons, `text-gray-700` when active. Remove the gold color entirely.
- "Create Repository" button: standard primary button, positioned in a header row:
  ```
  flex items-center justify-between mb-4
  ```
  Left: page title + count. Right: primary button.
- Empty state text: normal weight, `text-gray-500`, not italic
- Count badge: `text-sm text-gray-500` next to the title, or in a neutral badge

### Page 07: Repository Create (Recipe Selection)

**What's wrong:**
- This page is extremely dense and hard to parse -- it shows a grid of all format+type combinations as colored text links
- Color coding (green for "Hosted", blue for "Proxy", orange for "Group") is inconsistent and uses saturated colors that look garish
- Text is very small and tightly packed
- No visual grouping -- Maven, NPM, PyPI, Raw are all in one flat grid

**Redesign spec:**
- Restructure as a two-step wizard:
  1. Step 1: Choose format (Maven, npm, PyPI, Raw) -- show as large clickable cards in a 2x2 or 4-column grid. Each card has the format icon, name, and a one-line description.
  2. Step 2: Choose type (Hosted, Proxy, Group) -- show as 3 cards in a row with icon, type name, and description.
- If a two-step wizard is too much work, at minimum: group by format with clear section headings, and use cards instead of text links:
  ```
  <div class="grid grid-cols-3 gap-3">
    <button class="p-4 border border-gray-200 rounded-lg hover:border-accent-600 hover:bg-accent-50 text-left">
      <div class="text-sm font-medium text-gray-900">Hosted</div>
      <div class="text-xs text-gray-500">Store your own artifacts</div>
    </button>
    ...
  </div>
  ```
- Remove the colored text. Use the same `gray-900` text for all options, differentiate with icons or subtle background colors.

### Page 08: Blob Stores

**What's wrong:**
- "Delete" button is a solid red pill -- too aggressive for a table action. Looks like an error indicator, not a button.
- "file" type link is blue and underlined -- unclear why it is a link
- Same table styling issues as Page 06 (gold sort arrows, etc.)

**Redesign spec:**
- Table per standard spec
- Delete action: use a ghost button with red text: `text-error-600 hover:bg-error-50 text-xs font-medium px-2 py-1 rounded`
- Or better: use a three-dot menu (kebab menu) at the row end, with "Delete" as a menu item. This is standard for destructive table actions.
- "file" type: render as a neutral badge (`bg-gray-100 text-gray-700 text-xs px-2 py-0.5 rounded-sm`), not a link
- Add a "Create Blob Store" primary button in the header (consistent with other list pages)

### Page 09: Cleanup Policies

**What's wrong:**
- Two "Create Policy" buttons -- one in the header (blue pill, far right) and one in the empty state. Redundant.
- Empty state icon appears to be a rendered broom/cleanup emoji -- dated
- The "Create Policy" button in the empty state has a dotted/dashed border style that looks broken

**Redesign spec:**
- Keep only the header "Create Policy" button as a standard primary button
- Empty state: use the standard empty state component with a proper SVG icon (trash/clean icon)
- Remove the dotted-border CTA from the empty state body, or replace it with a standard primary button

### Page 10: Routing Rules

**What's wrong:**
- Same issues as other table pages (gold sort arrows, blue pill button)
- "Create Rule" button is too small and uses a non-standard style

**Redesign spec:**
- Identical treatment to Page 06 (Repositories). Standard table, standard header with button.

### Page 11: Users

**What's wrong:**
- "CHANGE_PASSWORD" status is shown as raw enum text with a red dot -- should be human-readable
- "Delete" buttons are solid red pills, same problem as Page 08
- Table is cramped -- columns like USER ID, NAME, EMAIL, STATUS, SOURCE, ROLES, and Delete all compete for space
- Role names ("nx-admin", "nx-anonymous") are shown as blue links -- unclear where they link

**Redesign spec:**
- Status column: use proper status badges
  - ACTIVE: green badge "Active"
  - CHANGE_PASSWORD: warning badge "Password Change Required"
- Roles: render as neutral badges (`bg-gray-100 text-gray-700 text-xs rounded-sm px-2 py-0.5`), spaced with `gap-1`
- Delete: kebab menu or ghost destructive button (not solid red)
- Add a "Create User" primary button in the header
- Consider hiding SOURCE column by default if all are "default" -- or render as a small badge

### Page 12: Roles

**What's wrong:**
- "1 privilege" / "2 privileges" shown as blue links -- unclear destination
- Same table styling issues
- No "Create Role" button visible

**Redesign spec:**
- Privileges: show as a neutral badge with count, not a link. E.g., `bg-gray-100 text-gray-600` badge showing "1 privilege"
- If clicking should open a detail view, make the entire row clickable (with `cursor-pointer hover:bg-gray-50`)
- Add "Create Role" primary button in header

### Page 13: LDAP Configuration

**What's wrong:**
- Empty state uses a rendered monitor/computer emoji icon -- looks like clipart
- "LDAP server configuration will be available in a future release." -- fine message, but the icon undermines it

**Redesign spec:**
- Empty state: SVG server/shield icon, 48px, `text-gray-300`
- Title: "No LDAP Servers Configured"
- Description: "LDAP server configuration will be available in a future release."
- No action button (feature not available yet) -- this is correct
- Consider adding a subtle info banner instead: `bg-info-50 border border-info-200 rounded-lg p-4` with an info icon

### Page 14: SSL Certificates

**What's wrong:**
- Same as LDAP -- rendered padlock emoji icon looks unprofessional
- Otherwise identical structure

**Redesign spec:**
- Same treatment as LDAP. SVG lock/shield icon.

### Page 15: Anonymous Access

**What's wrong:**
- Form inputs use underline-only style -- inconsistent with what bordered inputs should look like
- "NexusAuthorizingRealm" is a raw internal value exposed to the user -- confusing
- "Save" button is a small blue pill pushed to the far right with no visual relationship to the form
- Checkbox is the default browser checkbox -- looks different across browsers
- No form grouping or card wrapper -- fields float in open space

**Redesign spec:**
- Wrap form in a card: `bg-white rounded-lg border border-gray-200 p-6`
- Inputs: full bordered style per component spec
- Labels: proper label styling with `mb-1.5` gap to input
- Checkbox: use a styled checkbox (Tailwind forms plugin or custom). At minimum: `rounded border-gray-300 text-accent-600 focus:ring-accent-600`
- "Realm Name" field: use a dropdown/select if there are predefined values, not a free text input showing internal class names
- Save button: place at the bottom-left of the card (not far right), standard primary button
- Add a section heading above the form: "Settings" or similar
- Consider adding help text under each field to explain what Anonymous User ID and Realm Name mean

### Page 16: System Status

**What's wrong:**
- Stat cards at the top use inconsistent icon styles -- a green checkmark circle, an amber box emoji, and a brown package emoji
- The "Details" section below is a plain key-value table with no visual container
- "UP" status text is bold green with no badge styling
- Redundant information: the stat cards show the same data as the details table below

**Redesign spec:**
- Stat cards: use the standard dashboard card component with proper SVG icons
  - System Health: green circle + heart icon, value "Healthy"
  - Version: blue circle + tag icon, value "1.0.0"
  - Edition: purple circle + cube icon, value "MegaRepo"
- Details table: wrap in a card (`bg-white rounded-lg border border-gray-200`). Use a definition-list layout:
  ```
  <dl class="divide-y divide-gray-100">
    <div class="px-4 py-3 flex justify-between">
      <dt class="text-sm text-gray-500">Status</dt>
      <dd class="text-sm font-medium text-gray-900">UP (green badge)</dd>
    </div>
  </dl>
  ```
- Remove the stat cards entirely OR remove the details table. Showing the same data twice is wasteful. Recommendation: keep only the cards, add more useful system info to the details table (uptime, JVM memory, database status, disk usage).

### Page 17: Tasks

**What's wrong:**
- "WAITING" status is shown in bold blue text -- should be a proper badge
- "LAST RESULT" column shows solid colored pills ("OK" in green/blue) -- inconsistent with other badge styling
- Schedule column shows cron expressions ("0 0 1 * * ?") which are unreadable to most users
- Table is cramped

**Redesign spec:**
- State column: use status badges
  - WAITING: neutral badge (gray)
  - RUNNING: blue badge
  - FAILED: red badge
- Last Result column: use status badges (success-green for OK, error-red for FAILED)
- Schedule column: parse cron to human-readable text. E.g., "Daily at 1:00 AM" instead of "0 0 1 * * ?". Show the raw cron in a tooltip on hover.
- Add a "Run Now" ghost button per row if manual execution is supported

### Page 18: Audit Log

**What's wrong:**
- Same table styling issues (gold sort arrows)
- Empty state just says "No audit entries" as plain italic text in the table body -- not a proper empty state

**Redesign spec:**
- Standard table component
- Empty state: use the centered empty state component. Icon: clipboard/log icon. Message: "No audit entries yet. Activity will appear here as users interact with repositories."
- Add date range filter controls above the table

### Page 19: License

**What's wrong:**
- Three stat-like cards at top with inconsistent styling
- "Community Edition" label appears as a yellow/amber badge below the cards AND as a card stat -- redundant
- The edition comparison table at the bottom has two columns side by side but uses a mix of bullet points and colored "Contact Sales" / "Current" buttons
- "Drop your license file here or click to browse" upload area has no visual container/dashed border
- Overall page is very busy with too many visual elements competing for attention

**Redesign spec:**
- Simplify to two sections:
  1. **Current License** card: single card showing edition, status, and key details in a clean definition list
  2. **Upgrade** section (only if on Community): a simple side-by-side comparison with clear visual hierarchy
- License upload: use a proper drag-and-drop zone with dashed border:
  ```
  border-2 border-dashed border-gray-300 rounded-lg p-8 text-center
  hover:border-accent-600 hover:bg-accent-50/50
  ```
- Remove the stat cards -- they add no value over a simple card layout
- Edition comparison: use a clean two-column card layout, not bullet points

### Page 20: Account

**What's wrong:**
- Almost completely empty -- just shows "Username: admin" and nothing else
- No ability to change password, email, or other profile settings visible
- "Profile" section heading has no visual container
- Massive empty whitespace

**Redesign spec:**
- Wrap in a card: `bg-white rounded-lg border border-gray-200 p-6 max-w-2xl`
- Show profile fields: Username (read-only), Email (editable), Name (editable)
- Add a "Change Password" section below with current password + new password + confirm fields
- Add an "API Tokens" section for generating personal access tokens (common in repo managers)
- If those features do not exist yet, at minimum show the available data in a proper definition list inside a card, and add placeholder sections with "Coming soon" badges

---

## 3. Priority Order (Maximum Impact, Minimum Effort)

### Tier 1: Do These First (1-2 days, transforms the product feel)

1. **Global: Table component** -- Fix all tables at once (Pages 06, 08, 10, 11, 12, 17, 18). Remove gold sort arrows, apply consistent header/row/cell styling. This is a single component change that fixes 7 pages.

2. **Global: Button component** -- Replace all blue pills and red delete pills with properly styled buttons. Affects every page with actions.

3. **Global: Form input component** -- Replace all underline inputs with bordered inputs. Affects Pages 01, 03, 04, 06, 08, 09, 10, 15, 18.

4. **Global: Empty state component** -- Create one reusable empty state with SVG icon + title + description + optional CTA. Replace all emoji/image empty states. Affects Pages 03, 05, 09, 13, 14, 18.

5. **Page 01: Login** -- First impression. Apply the card + input fixes.

### Tier 2: High Impact (2-3 days)

6. **Page 02: Dashboard** -- Replace emoji stat icons with SVG icons in colored circles. Fix card spacing.

7. **Page 04: Search** -- Fix the broken tab navigation (tabs are concatenated text).

8. **Page 07: Repo Create** -- Restructure from a text grid to a card-based selector.

9. **Page 16: Status** -- Fix stat cards and remove redundancy.

10. **Page 11: Users** -- Fix status display (CHANGE_PASSWORD), role badges, delete buttons.

### Tier 3: Polish (1-2 days)

11. **Page 15: Anonymous Access** -- Wrap form in card, fix inputs.
12. **Page 17: Tasks** -- Fix badges, humanize cron expressions.
13. **Page 19: License** -- Simplify layout.
14. **Page 20: Account** -- Add card wrapper, flesh out content.
15. **Pages 08, 09, 10, 12** -- Already fixed by the global table/button/empty-state changes.

---

## 4. Anti-Patterns to Eliminate

### 4.1 Emoji and Rendered Image Icons
**Problem:** Folder, lock, broom, monitor, arrow, and package icons are rendered emoji or low-res images. They look different on every OS, cannot be color-controlled, and scream "prototype."
**Fix:** Replace ALL icons with a single SVG icon set. Recommended: [Heroicons](https://heroicons.com/) (by the Tailwind team, MIT license, designed for Tailwind). Use the "outline" variant at 20px for inline, 24px for standalone. Already available as copy-paste SVG -- no library install needed.

### 4.2 Gold/Yellow Sort Arrows in Tables
**Problem:** Table sort indicators use a gold/yellow triangle character that clashes with the blue-gray palette and looks like a warning indicator.
**Fix:** Use `text-gray-400` chevron SVGs. Active sort direction uses `text-gray-700`.

### 4.3 Solid Red Delete Buttons
**Problem:** Bright red filled buttons in table rows draw the eye to the most destructive action on the page. They look like error indicators, not actionable buttons.
**Fix:** Use ghost destructive buttons (`text-error-600` with no background, or a subtle `hover:bg-error-50`). Better yet, hide destructive actions behind a kebab menu.

### 4.4 Underline-Only Inputs (Material Design Holdover)
**Problem:** Some inputs have only a bottom border. This is a Material Design convention that does not match the rest of the UI and looks unfinished without the full Material component system.
**Fix:** All inputs use the bordered style: `border border-gray-200 rounded-md`.

### 4.5 Blue Pill Buttons With No Padding Consistency
**Problem:** Action buttons ("+ Create Repository", "Create Rule", "Save", "Search") are small, tightly padded, and use inconsistent border-radius. Some are rounded-full (pills), some are rounded-md.
**Fix:** All buttons use `rounded-md` (6px). All use the same padding scale. No pills for action buttons (pills are reserved for badges/tags).

### 4.6 Concatenated Text for Tab Navigation
**Problem:** On the Search page, tabs "Keyword", "Maven", "npm", "PyPI" render as a single run-on string "KeywordMavennpmPyPI" with no spacing or visual separation.
**Fix:** Proper tab component with `border-b-2` underline for active state, proper spacing, and hover states.

### 4.7 Raw Internal Values Exposed to Users
**Problem:** "CHANGE_PASSWORD" status, "NexusAuthorizingRealm" realm name, cron expressions like "0 0 1 * * ?" are shown as-is.
**Fix:** Map all internal values to human-readable labels. Show technical values in tooltips or expandable details if needed by power users.

### 4.8 Redundant Empty State CTAs
**Problem:** Some empty states show both a text link ("Create a repository to get started") AND a button ("Create Repository"), or a header button AND an empty-state button pointing to the same action.
**Fix:** One CTA per context. If the page has a header button, the empty state should have just explanatory text (no duplicate button). If there is no header button, the empty state should have the primary CTA.

### 4.9 Inconsistent Page Header Pattern
**Problem:** Some pages show `Title + subtitle + filter + action button`, others show just `Title + subtitle`. The arrangement varies (button far-right vs. centered, filter width varies).
**Fix:** Standardize the page header layout:
```
<div class="flex items-start justify-between mb-6">
  <div>
    <h1 class="text-2xl font-semibold text-gray-950">{title}</h1>
    <p class="text-sm text-gray-500 mt-1">{subtitle}</p>
  </div>
  <div>{action button, if applicable}</div>
</div>
```

### 4.10 No Content Containers
**Problem:** Form fields and content float directly on the page background with no visual grouping. This makes pages like Anonymous Access and Account look empty and unstructured.
**Fix:** Wrap related content in cards (`bg-white rounded-lg border border-gray-200 p-6`). Use section headings to label card groups.

### 4.11 ALPHA Badge in Sidebar Footer
**Problem:** A red "ALPHA" badge sits in the sidebar footer. For a commercial product, this undermines confidence.
**Fix:** If the product is genuinely in alpha/beta, use a subtle neutral badge in the header bar instead (e.g., `bg-gray-100 text-gray-600 text-xs rounded-sm px-1.5 py-0.5` showing "Beta"). Remove it entirely once the product ships.

---

## 5. Implementation Notes

### CSS Strategy
- Remove ALL legacy/custom CSS. Go 100% Tailwind utility classes.
- If you find yourself writing `@apply` for more than 5 properties, create a React component instead.
- Use Tailwind's `@layer components` only for truly global resets (e.g., base input styles).

### Component Extraction Priority
Create these reusable React components first:
1. `<PageHeader title subtitle action />` -- standardizes every page top
2. `<DataTable columns data emptyState />` -- standardizes all 8+ table pages
3. `<EmptyState icon title description action />` -- standardizes all empty views
4. `<StatusBadge variant label />` -- standardizes all status displays
5. `<FormField label helpText error children />` -- standardizes all form layouts

### Font Loading
Add to `<head>`:
```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
```

Add to Tailwind config:
```js
theme: {
  fontFamily: {
    sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
  },
}
```

### Icon Strategy
Download the needed Heroicons as individual SVG files into `src/assets/icons/`. Do NOT install the full Heroicons React package unless bundle size is not a concern. Needed icons (approximately 20):
- `archive-box` (repositories)
- `cube` (components/packages)
- `server` (blob stores, LDAP)
- `circle-stack` (database/storage)
- `shield-check` (security, SSL)
- `users` (users)
- `key` (roles, permissions)
- `clock` (tasks, schedules)
- `document-text` (audit log)
- `arrow-up-tray` (upload)
- `magnifying-glass` (search)
- `folder-open` (browse)
- `chart-bar` (dashboard)
- `cog-6-tooth` (settings)
- `trash` (delete)
- `plus` (create)
- `chevron-right` (navigation)
- `chevron-up` / `chevron-down` (sort)
- `ellipsis-vertical` (kebab menu)
- `x-mark` (close/dismiss)
- `check-circle` (success/healthy)
- `exclamation-triangle` (warning)
- `lock-closed` (SSL)
- `heart` (health)
