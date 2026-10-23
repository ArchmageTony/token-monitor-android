// Release tooling for forks of this app. The upstream tools (release.json, check-release.mjs,
// release-preflight.mjs) stay untouched and pinned to the upstream identity; this file derives the
// fork's identity at run time instead: the repository from GITHUB_REPOSITORY (or `gh repo view`
// locally), the package from app/build.gradle.kts, the version from gradle.properties plus
// fork.properties, and the signing certificate from RELEASE_CERT_SHA256 (an Actions variable).
//
//   node tools/fork-release.mjs check
//   node tools/fork-release.mjs preflight [--sha <commit>] [--tag-push]
//   node tools/fork-release.mjs prepare --apk <app-release.apk> --out <dir>
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { appendFile, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import { createUpdateManifest, parseApkIdentity, parseProperties, validateApkIdentity, validateBundle,
    validateMetadata } from "./release-metadata.mjs";
import { requireSuccessfulJobs } from "./release-preflight.mjs";

export const upstreamRepository = "The-Minion-oOo/token-monitor-android";
export const forkRevisionOffset = 500;
const tagPattern = /^android-v(\d+)\.(\d+)\.(\d+)-r(\d+)$/;
/** Runs only on main pushes, so a pre-merge (pull request) build cannot have it. */
const mainOnlyWorkflows = new Set(["hub-contract.yml"]);

export function forkVersionCode(upstreamVersionCode, forkRevision) {
    assert.ok(Number.isSafeInteger(upstreamVersionCode) && upstreamVersionCode > 0, "Invalid upstream version code");
    assert.ok(upstreamVersionCode % 1000 < forkRevisionOffset, "Upstream revision reached the fork range (500-999)");
    assert.ok(Number.isSafeInteger(forkRevision) && forkRevision >= 1 && forkRevision <= 499, "Fork revision must be 1-499");
    return Math.floor(upstreamVersionCode / 1000) * 1000 + forkRevisionOffset + forkRevision;
}

export function forkTag(versionName, versionCode) {
    assert.match(versionName, /^v\d+\.\d+\.\d+$/);
    return `android-${versionName}-r${versionCode % 1000}`;
}

export function compareTags(left, right) {
    const parts = tag => { const match = tagPattern.exec(tag); assert.ok(match, `Unexpected release tag: ${tag}`); return match.slice(1).map(Number); };
    const a = parts(left), b = parts(right);
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return Math.sign(a[i] - b[i]);
    return 0;
}

export function validateRepository(repository) {
    assert.match(repository || "", /^[A-Za-z0-9-]+\/[A-Za-z0-9._-]+$/, "Repository must be owner/name");
    assert.notEqual(repository.toLowerCase(), upstreamRepository.toLowerCase(), "Fork releases never target the upstream repository");
    return repository;
}

export function validateCertificate(certificate) {
    const normalized = String(certificate || "").trim().toLowerCase().replaceAll(":", "");
    assert.match(normalized, /^[a-f0-9]{64}$/, "RELEASE_CERT_SHA256 must be a 64-character SHA-256 hex digest");
    return normalized;
}

export function parseApplicationId(gradle) {
    const match = /^\s*applicationId\s*=\s*"([A-Za-z0-9_.]+)"/m.exec(gradle);
    assert.ok(match, "applicationId not found in app/build.gradle.kts");
    return match[1];
}

/** The release object shape used by release-metadata.mjs, built from the fork's own identity. */
export function forkRelease({ repository, packageName, certificate, properties, forkProperties }) {
    const revision = Number(forkProperties.tokenMonitorForkRevision);
    const versionCode = forkVersionCode(Number(properties.tokenMonitorVersionCode), revision);
    const versionName = properties.tokenMonitorVersionName;
    return { schemaVersion: 1, repository, packageName, signingCertificateSha256: certificate,
        candidate: { status: "candidate", tag: forkTag(versionName, versionCode), versionName, versionCode } };
}

export function releaseTitle(release) {
    return `Android ${release.candidate.versionName} r${release.candidate.versionCode % 1000} (Remote)`;
}

/**
 * Required CI for a fork draft. A commit on main needs every upstream-required job from its main push runs.
 * A pre-merge commit needs the same jobs from its pull request runs, except main-only workflows.
 */
export function requireForkChecks(requiredChecks, sha, onMain, runsByWorkflow, jobsByRun) {
    const checked = [];
    for (const check of requiredChecks) {
        const runs = runsByWorkflow.get(check.workflow) || [];
        if (onMain) {
            requireSuccessfulJobs(check, sha, runs, jobsByRun);
            checked.push(check.workflow);
            continue;
        }
        if (mainOnlyWorkflows.has(check.workflow)) continue;
        // Same rules as a main release (latest attempt, every job, exact commit), applied to the pull request runs.
        const pullRequestRuns = runs.filter(run => run.head_sha === sha && run.event === "pull_request")
            .map(run => ({ ...run, head_branch: "main", event: "push" }));
        requireSuccessfulJobs(check, sha, pullRequestRuns, jobsByRun);
        checked.push(check.workflow);
    }
    assert.ok(checked.length > 0, "No required checks were verified");
    return checked;
}

/** No existing release (draft or published) for this tag, and newer than the latest published one. */
export function requireNewRelease(tag, releases, latestTag) {
    assert.ok(!releases.some(release => release.tag_name === tag), `A release for ${tag} already exists`);
    if (latestTag) assert.ok(compareTags(tag, latestTag) > 0, `${tag} must be newer than the published ${latestTag}`);
}

async function loadForkRelease(root, certificate) {
    const [properties, forkProperties, upstream, gradle, upstreamRelease] = await Promise.all([
        readFile(join(root, "gradle.properties"), "utf8").then(parseProperties),
        readFile(join(root, "fork.properties"), "utf8").then(parseProperties),
        readFile(join(root, "upstream.json"), "utf8").then(JSON.parse),
        readFile(join(root, "app/build.gradle.kts"), "utf8"),
        readFile(join(root, "release.json"), "utf8").then(JSON.parse),
    ]);
    // The upstream records still have to agree with Gradle and the verified desktop pin.
    validateMetadata(upstreamRelease, properties, upstream);
    const packageName = parseApplicationId(gradle);
    assert.notEqual(packageName, upstreamRelease.packageName, "The fork must not reuse the upstream package");
    const repository = validateRepository(process.env.GITHUB_REPOSITORY || repositoryFromGh());
    const release = forkRelease({ repository, packageName, certificate, properties, forkProperties });
    const installer = await readFile(join(root, "app/src/main/java/io/github/theminionooo/tokenmonitor/data/update/ReleaseUpdates.kt"), "utf8");
    assert.ok(!installer.includes(upstreamRelease.signingCertificateSha256), "Installer still pins the upstream certificate");
    assert.ok(installer.includes("BuildConfig.UPDATE_REPOSITORY") && installer.includes("BuildConfig.UPDATE_CERT_SHA256"),
        "Installer identity must come from BuildConfig");
    return { release, upstream, upstreamRelease };
}

function repositoryFromGh() {
    try {
        return execFileSync("gh", ["repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"], { encoding: "utf8" }).trim();
    } catch {
        return "";
    }
}

async function github(path, { allowMissing = false } = {}) {
    const token = process.env.GH_TOKEN;
    assert.ok(token, "GH_TOKEN is required for the release preflight");
    const response = await fetch(`https://api.github.com/${path}`, { headers: {
        Accept: "application/vnd.github+json", Authorization: `Bearer ${token}`,
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "token-monitor-fork-release",
    }, signal: AbortSignal.timeout(20_000), redirect: "error" });
    if (allowMissing && response.status === 404) return null;
    assert.ok(response.ok, `GitHub request failed (${response.status}): ${path.split("?")[0]}`);
    return response.json();
}

async function preflight(root, values) {
    const { release, upstreamRelease } = await loadForkRelease(root, "0".repeat(64));
    const { repository, candidate: { tag } } = release;
    const sha = values.sha || process.env.GITHUB_SHA;
    assert.match(sha || "", /^[a-f0-9]{40}$/, "Provide the complete release commit SHA");
    const base = `repos/${repository}`;
    if (values["tag-push"]) {
        assert.equal(process.env.GITHUB_REF, `refs/tags/${tag}`, `Pushed tag does not match this build (${tag})`);
        const ref = await github(`${base}/git/ref/tags/${tag}`);
        const target = ref.object.type === "tag" ? (await github(`${base}/git/tags/${ref.object.sha}`)).object.sha : ref.object.sha;
        assert.equal(target, sha, "The tag no longer points at this commit");
    } else {
        assert.equal(process.env.GITHUB_REF, "refs/heads/main", "Non-dry-run dispatches are only for main; push a tag for other commits");
        assert.equal(await github(`${base}/git/ref/tags/${tag}`, { allowMissing: true }), null, `Tag ${tag} already exists`);
    }
    const releases = await github(`${base}/releases?per_page=100`);
    const latest = await github(`${base}/releases/latest`, { allowMissing: true });
    requireNewRelease(tag, releases, latest?.tag_name);
    const comparison = await github(`${base}/compare/main...${sha}`);
    const onMain = ["identical", "behind"].includes(comparison.status);
    const runsByWorkflow = new Map();
    const jobsByRun = new Map();
    for (const check of upstreamRelease.requiredChecks) {
        if (!onMain && mainOnlyWorkflows.has(check.workflow)) continue;
        const result = await github(`${base}/actions/workflows/${check.workflow}/runs?head_sha=${sha}&per_page=100`);
        const runs = result.workflow_runs || [];
        runsByWorkflow.set(check.workflow, runs);
        for (const run of runs) {
            const jobs = [];
            for (let page = 1; ; page++) {
                const response = await github(`${base}/actions/runs/${run.id}/attempts/${run.run_attempt}/jobs?per_page=100&page=${page}`);
                jobs.push(...response.jobs);
                if (!response.jobs.length || jobs.length >= response.total_count) break;
            }
            jobsByRun.set(run.id, jobs);
        }
    }
    const checked = requireForkChecks(upstreamRelease.requiredChecks, sha, onMain, runsByWorkflow, jobsByRun);
    console.log(`${tag}: ${onMain ? "main" : "pre-merge (pull request)"} checks passed on ${sha}: ${checked.join(", ")}.`);
    if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT, `source=${onMain ? "main" : "pull-request"}\n`);
}

function buildTools() {
    const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME;
    assert.ok(sdk, "ANDROID_SDK_ROOT or ANDROID_HOME is required to inspect the APK");
    return readdir(join(sdk, "build-tools")).then(versions => {
        const stable = versions.filter(version => /^\d+\.\d+\.\d+$/.test(version)).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
        assert.ok(stable.length, "No stable Android build-tools found");
        return join(sdk, "build-tools", stable.at(-1));
    });
}

export function releaseNotes(release, upstream, baseNotes, source) {
    const lines = [baseNotes.trim(), "", "---", "",
        `- Package: \`${release.packageName}\``,
        `- Version: ${release.candidate.versionName} (versionCode ${release.candidate.versionCode})`,
        `- Upstream baseline: ${upstream.repository} ${upstream.tag} (\`${upstream.commit}\`)`,
        `- Signing certificate SHA-256: \`${release.signingCertificateSha256}\``];
    if (source === "pull-request") lines.push("- Built from a commit that has not been merged to main yet (pull request checks passed).");
    return `${lines.join("\n")}\n`;
}

/**
 * apksigner 36 prints "Signer #1 certificate ...", apksigner 37 prints one line per signature scheme
 * ("V3.0 Signer: certificate ..."). Reduce either to the distinct certificates in the older form, so a
 * second key, including a rotation lineage, still fails the single-signer check.
 */
export function signerLines(output) {
    const digests = new Set([...output.matchAll(/^(?:Signer #\d+|V\d+(?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([a-fA-F0-9:]+)\s*$/gm)]
        .map(match => match[1].replaceAll(":", "").toLowerCase()));
    return [...digests].map((digest, index) => `Signer #${index + 1} certificate SHA-256 digest: ${digest}`).join("\n");
}

async function prepare(root, values) {
    assert.ok(values.apk && values.out, "prepare needs --apk and --out");
    const certificate = validateCertificate(process.env.RELEASE_CERT_SHA256);
    const { release, upstream } = await loadForkRelease(root, certificate);
    const tools = await buildTools();
    const apk = resolve(values.apk);
    const badging = execFileSync(join(tools, "aapt"), ["dump", "badging", apk], { encoding: "utf8" });
    const certificates = execFileSync(join(tools, "apksigner"), ["verify", "--print-certs", apk], { encoding: "utf8" });
    validateApkIdentity(parseApkIdentity(badging, signerLines(certificates)), release);
    const bytes = await readFile(apk);
    const manifest = createUpdateManifest(release, bytes);
    const out = resolve(values.out);
    await mkdir(out, { recursive: true });
    const apkPath = join(out, manifest.apk);
    const manifestPath = join(out, "token-monitor-android-update.json");
    await writeFile(apkPath, bytes);
    await writeFile(`${apkPath}.sha256`, `${manifest.sha256}  ${manifest.apk}\n`);
    await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
    validateBundle(release, await readFile(apkPath), JSON.parse(await readFile(manifestPath, "utf8")),
        await readFile(`${apkPath}.sha256`, "utf8"), apkPath);
    const baseNotes = await readFile(join(root, `docs/releases/${release.candidate.tag}.md`), "utf8");
    const notesPath = join(out, "release-notes.md");
    await writeFile(notesPath, releaseNotes(release, upstream, baseNotes, process.env.RELEASE_SOURCE));
    if (process.env.GITHUB_OUTPUT) await appendFile(process.env.GITHUB_OUTPUT,
        `tag=${manifest.tag}\napk=${apkPath}\nmanifest=${manifestPath}\nnotes=${notesPath}\ntitle=${releaseTitle(release)}\n`);
    console.log(`Verified ${manifest.apk}: ${release.packageName} ${manifest.versionName} (${manifest.versionCode}), signer ${certificate}, sha256 ${manifest.sha256}.`);
}

async function main() {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
    const { positionals, values } = parseArgs({ allowPositionals: true, options: {
        apk: { type: "string" }, out: { type: "string" }, sha: { type: "string" }, "tag-push": { type: "boolean" },
    } });
    const command = positionals[0];
    if (command === "check") {
        const { release } = await loadForkRelease(root, "0".repeat(64));
        await readFile(join(root, `docs/releases/${release.candidate.tag}.md`));
        console.log(`Fork release metadata agrees with Gradle, upstream and the installer: ${release.repository} ${release.packageName} ${release.candidate.tag} (${release.candidate.versionCode}).`);
    } else if (command === "preflight") await preflight(root, values);
    else if (command === "prepare") await prepare(root, values);
    else throw new Error("Usage: fork-release.mjs check | preflight [--sha SHA] [--tag-push] | prepare --apk APK --out DIR");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
