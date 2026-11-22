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

test("Unauthenticated user: cannot read or write any VoxBridge collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 1000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("recordings").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("voice_models").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      preferredLanguage: "en",
      createdAt: past,
      updatedAt: past,
    })
  );
});

test("UserProfile: owner create, partial update, cross-user denial, and ghost field denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Speaker",
      preferredLanguage: "en",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Partial update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      preferredLanguage: "gu",
      activeModelId: "model_1",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Future",
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Cross-user denial
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("SpeechRecording & CustomPhrase: Kotlin-aligned create, list, update, delete & isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("custom_phrases").doc("cp_1").set({
      userId: ALICE_UID,
      text: "Please bring warm tea",
      language: "en",
      category: "Daily",
      createdAt: past,
      updatedAt: past,
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("custom_phrases").get()
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_1").set({
      userId: ALICE_UID,
      phraseKey: "en_0",
      phraseText: "Please pack bright paper bags quickly.",
      language: "en",
      localFilePath: "/data/user/0/com.aistudio.voxbridge.rkmpzq/files/voice_recordings/rec_1.wav",
      durationMs: 3200,
      sampleRateHz: 16000,
      peakAmplitude: 18420,
      phoneticNotes: "Clear consonant onset",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Reject @DocumentId 'id' inside payload
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_2").set({
      id: "rec_2",
      userId: ALICE_UID,
      phraseKey: "en_1",
      phraseText: "Top chefs chop crisp garlic cloves.",
      language: "en",
      localFilePath: "/tmp/rec_2.wav",
      durationMs: 2500,
      sampleRateHz: 16000,
      peakAmplitude: 14000,
      createdAt: past,
      updatedAt: past,
    })
  );

  // Bob cannot list or read Alice's recordings
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("recordings").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_1").get());

  // Alice can delete her own recording
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_1").delete()
  );
});

test("VoiceModelProfile: training, retrain, terminal ARCHIVED lock, and delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("voice_models").doc("vm_1").set({
      userId: ALICE_UID,
      name: "Carbon Deep Profile",
      architecture: "DEEP_PHONETIC",
      geminiModelId: "gemini-4-carbon",
      language: "en",
      trainingSampleCount: 12,
      phoneticProfileSummary: "Softens final stops and lengthens medial vowels.",
      adaptationPrompt: "Speaker tends to devoice final /d/ and soften /θ/.",
      articulationPatterns: ["Final /d/ -> /t/ softening", "Prolonged vowel transitions"],
      confidenceScore: 88,
      status: "READY",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Rename action
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("voice_models").doc("vm_1").update({
      name: "Carbon Deep v2",
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Archive action (terminal state)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("voice_models").doc("vm_1").update({
      status: "ARCHIVED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Subsequent update on ARCHIVED must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("voice_models").doc("vm_1").update({
      name: "Reactivated",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cross-user delete denied, owner delete succeeds
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("voice_models").doc("vm_1").delete());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("voice_models").doc("vm_1").delete());
});

test("DictationEntry: create, update spokenAloud, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const past = new Date(Date.now() - 3000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("dictations").doc("dic_1").set({
      userId: ALICE_UID,
      modelId: "vm_1",
      modelName: "Carbon Deep Profile",
      language: "en",
      rawTranscript: "plea pa brigh paper bag",
      adaptedText: "Please pack bright paper bags.",
      spokenAloud: false,
      latencyMs: 620,
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("dictations").doc("dic_1").update({
      spokenAloud: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("dictations").get());
});
