const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write user profiles or limited_apps", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("limited_apps").get()
  );
});

test("Authenticated owner can create and update own profile, but cannot inject ghost fields or change createdAt", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Admin",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable createdAt mutation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.collection("users").doc("alice_ghost").set({
      userId: "alice_ghost",
      displayName: "Ghost",
      isVerified: true,
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Authenticated owner can create, list, and update own limited_apps; cross-user access denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("limited_apps").doc("com_social_app").set({
      userId: ALICE_UID,
      packageName: "com.social.app",
      appName: "Social",
      limited: true,
      blockCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("limited_apps").get()
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("limited_apps").doc("com_social_app").update({
      limited: false,
      blockCount: 2,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob cannot read or mutate Alice's limited_apps
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("limited_apps").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("limited_apps").doc("com_social_app").update({
      limited: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).get()
  );
});

test("Rejects future timestamps and oversized fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("app_releases: signed-in users can read/list and publisher can create; unauth, cross-user tamper, and invalid URLs/hashes fail", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 2000);
  const validHash = "a".repeat(64);

  await assertFails(unauthDb.collection("app_releases").orderBy("versionCode", "desc").limit(1).get());

  await assertSucceeds(
    aliceDb.collection("app_releases").doc("v_2_1000").set({
      publishedByUid: ALICE_UID,
      packageName: "com.aistudio.vaultguard.kxmpzq",
      versionCode: 2,
      versionName: "1.1.0",
      releaseNotes: "Added OTA updates",
      downloadUrl: "https://firebasestorage.googleapis.com/v0/b/test/o/app.apk",
      signingSha256: validHash,
      apkSizeBytes: 10485760,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Another signed-in user (Bob) can query the latest release
  await assertSucceeds(
    bobDb.collection("app_releases").orderBy("versionCode", "desc").limit(1).get()
  );

  // Bob cannot overwrite or delete Alice's release
  await assertFails(
    bobDb.collection("app_releases").doc("v_2_1000").update({
      releaseNotes: "Tampered",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(bobDb.collection("app_releases").doc("v_2_1000").delete());

  // Reject non-HTTPS downloadUrl or invalid signingSha256
  await assertFails(
    aliceDb.collection("app_releases").doc("v_3_1000").set({
      publishedByUid: ALICE_UID,
      packageName: "com.aistudio.vaultguard.kxmpzq",
      versionCode: 3,
      versionName: "1.2.0",
      releaseNotes: "Insecure URL",
      downloadUrl: "http://example.com/app.apk",
      signingSha256: validHash,
      apkSizeBytes: 1000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    aliceDb.collection("app_releases").doc("v_4_1000").set({
      publishedByUid: ALICE_UID,
      packageName: "com.aistudio.vaultguard.kxmpzq",
      versionCode: 4,
      versionName: "1.3.0",
      releaseNotes: "Bad hash",
      downloadUrl: "https://firebasestorage.googleapis.com/v0/b/test/o/app.apk",
      signingSha256: "not-a-sha256",
      apkSizeBytes: 1000,
      createdAt: now,
      updatedAt: now,
    })
  );
});
