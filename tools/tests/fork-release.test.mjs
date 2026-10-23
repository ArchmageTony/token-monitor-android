import assert from "node:assert/strict";
import { test } from "node:test";
import { createUpdateManifest, parseApkIdentity, validateApkIdentity, validateBundle } from "../release-metadata.mjs";
import { compareTags, forkRelease, forkTag, forkVersionCode, parseApplicationId, releaseNotes, releaseTitle,
    requireForkChecks, requireNewRelease, validateCertificate, validateRepository } from "../fork-release.mjs";

const certificate = "c".repeat(64);
const properties = { tokenMonitorVersionCode: "770002", tokenMonitorVersionName: "v0.77.0" };
const release = forkRelease({ repository: "example/token-monitor-android", packageName: "io.example.tokenmonitor",
    certificate, properties, forkProperties: { tokenMonitorForkRevision: "1" } });

test("fork versions sit above the upstream revision range and keep the upstream tag format", () => {
    assert.equal(forkVersionCode(770002, 1), 770501);
    assert.equal(forkVersionCode(770002, 2), 770502);
    assert.equal(forkVersionCode(780001, 1), 780501);
    assert.equal(forkVersionCode(631001, 7), 631507);
    assert.equal(forkTag("v0.77.0", 770501), "android-v0.77.0-r501");
    assert.equal(release.candidate.tag, "android-v0.77.0-r501");
    assert.equal(release.candidate.versionCode, 770501);
    assert.equal(releaseTitle(release), "Android v0.77.0 r501 (Remote)");
    assert.ok(compareTags("android-v0.77.0-r501", "android-v0.77.0-r2") > 0);
    assert.ok(compareTags("android-v0.77.0-r501", "android-v0.78.0-r501") < 0);
    assert.equal(compareTags("android-v0.77.0-r501", "android-v0.77.0-r501"), 0);
});

test("revisions outside 1-499 and upstream revisions in the fork range are rejected", () => {
    for (const revision of [0, 500, -1, 1.5, Number.NaN]) assert.throws(() => forkVersionCode(770002, revision), /Fork revision/);
    assert.throws(() => forkVersionCode(770500, 1), /fork range/);
});

test("repository and certificate identities are validated", () => {
    assert.equal(validateRepository("example/token-monitor-android"), "example/token-monitor-android");
    for (const bad of ["", "no-slash", "a/b/c", "The-Minion-oOo/token-monitor-android", "the-minion-ooo/TOKEN-MONITOR-ANDROID"]) {
        assert.throws(() => validateRepository(bad));
    }
    assert.equal(validateCertificate(`${"AB:".repeat(31)}AB`), "ab".repeat(32));
    for (const bad of ["", "abc", "g".repeat(64), "a".repeat(63)]) assert.throws(() => validateCertificate(bad), /RELEASE_CERT_SHA256/);
    assert.equal(parseApplicationId('    defaultConfig {\n        applicationId = "io.example.app"\n'), "io.example.app");
    assert.throws(() => parseApplicationId("namespace = \"x\""));
});

test("assets keep the upstream names and a mismatched signer is refused", () => {
    const bytes = Buffer.from("synthetic fork APK");
    const manifest = createUpdateManifest(release, bytes);
    assert.equal(manifest.apk, "token-monitor-android-v0.77.0-r501.apk");
    assert.equal(manifest.packageName, "io.example.tokenmonitor");
    validateBundle(release, bytes, manifest, `${manifest.sha256}  ${manifest.apk}\n`, manifest.apk);
    const badging = "package: name='io.example.tokenmonitor' versionCode='770501' versionName='v0.77.0'";
    validateApkIdentity(parseApkIdentity(badging, `Signer #1 certificate SHA-256 digest: ${certificate}\n`), release);
    assert.throws(() => validateApkIdentity(parseApkIdentity(badging, `Signer #1 certificate SHA-256 digest: ${"d".repeat(64)}\n`), release),
        /signing certificate/);
    assert.throws(() => validateApkIdentity(parseApkIdentity(badging.replace("770501", "770002"), `Signer #1 certificate SHA-256 digest: ${certificate}\n`), release));
});

const sha = "1".repeat(40);
const checks = [{ workflow: "android.yml", jobs: ["build"] }, { workflow: "hub-contract.yml", jobs: ["contract"] }];
const run = (id, event, conclusion = "success", branch = "feature") => ({ id, head_sha: sha, head_branch: branch, event,
    run_number: id, run_attempt: 1, status: "completed", conclusion });
const job = (name, conclusion = "success") => ({ name, head_sha: sha, status: "completed", conclusion });

test("pre-merge drafts need passing pull request checks; main drafts need every main push check", () => {
    const prRuns = new Map([["android.yml", [run(1, "pull_request")]]]);
    assert.deepEqual(requireForkChecks(checks, sha, false, prRuns, new Map([[1, [job("build")]]])), ["android.yml"]);
    assert.throws(() => requireForkChecks(checks, sha, false, prRuns, new Map([[1, [job("build", "failure")]]])), /did not pass/);
    assert.throws(() => requireForkChecks(checks, sha, false, new Map([["android.yml", [run(1, "pull_request", "failure")]]]),
        new Map([[1, [job("build")]]])), /did not pass/);
    assert.throws(() => requireForkChecks(checks, sha, false, new Map(), new Map()), /No main push run/);
    const mainRuns = new Map([["android.yml", [run(1, "push", "success", "main")]], ["hub-contract.yml", [run(2, "push", "success", "main")]]]);
    const mainJobs = new Map([[1, [job("build")]], [2, [job("contract")]]]);
    assert.deepEqual(requireForkChecks(checks, sha, true, mainRuns, mainJobs), ["android.yml", "hub-contract.yml"]);
    assert.throws(() => requireForkChecks(checks, sha, true, prRuns, new Map([[1, [job("build")]]])), /No main push run/);
});

test("a tag is released once and only above the latest published release", () => {
    requireNewRelease("android-v0.77.0-r501", [], null);
    requireNewRelease("android-v0.77.0-r502", [{ tag_name: "android-v0.77.0-r501" }], "android-v0.77.0-r501");
    assert.throws(() => requireNewRelease("android-v0.77.0-r501", [{ tag_name: "android-v0.77.0-r501", draft: true }], null), /already exists/);
    assert.throws(() => requireNewRelease("android-v0.77.0-r501", [], "android-v0.77.0-r502"), /newer/);
});

test("release notes carry the baseline and certificate without personal details", () => {
    const notes = releaseNotes(release, { repository: "Javis603/token-monitor", tag: "v0.77.0", commit: "c".repeat(40) }, "# Notes\n", "pull-request");
    assert.match(notes, new RegExp(certificate));
    assert.match(notes, /Javis603\/token-monitor v0\.77\.0/);
    assert.match(notes, /not been merged/);
    assert.doesNotMatch(releaseNotes(release, { repository: "x/y", tag: "v1.0.0", commit: "c".repeat(40) }, "# Notes", "main"), /not been merged/);
});

test("reads the signer from apksigner 36 and 37 output", async () => {
    const { signerLines } = await import("../fork-release.mjs");
    const a = "a".repeat(64), b = "b".repeat(64);
    assert.equal(signerLines(`Signer #1 certificate DN: CN=x\nSigner #1 certificate SHA-256 digest: ${a}\n`), `Signer #1 certificate SHA-256 digest: ${a}`);
    assert.equal(signerLines(`V3.0 Signer: certificate DN: CN=x\nV3.0 Signer: certificate SHA-256 digest: ${a}\nV3.1 Signer: certificate SHA-256 digest: ${a}\n`),
        `Signer #1 certificate SHA-256 digest: ${a}`);
    assert.equal(signerLines(`V3.0 Signer: certificate SHA-256 digest: ${a}\nV3.1 Signer: certificate SHA-256 digest: ${b}\n`).split("\n").length, 2);
    assert.equal(signerLines("nothing here"), "");
});
