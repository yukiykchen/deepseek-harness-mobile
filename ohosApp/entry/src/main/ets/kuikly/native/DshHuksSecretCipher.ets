import huks from '@ohos.security.huks';

const KEY_ALIAS = 'io.github.yukiykchen.dsh.relay.kek.v1';
const TAG_BYTES = 16;
let keyCreation: Promise<void> | null = null;

/** HUKS generates and retains the AES key; neither encryption nor decryption exports its bytes. */
export class DshHuksSecretCipher {
  async seal(iv: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
    await ensureKey();
    return operate(true, iv, plaintext);
  }
  async open(iv: Uint8Array, ciphertext: Uint8Array): Promise<Uint8Array> {
    // Missing keys are an error, not a reason to regenerate one and fall back to legacy storage.
    if (!await huks.isKeyItemExist(KEY_ALIAS, {})) throw new Error('Protected pairing key unavailable');
    return operate(false, iv, ciphertext);
  }
}

async function ensureKey(): Promise<void> {
  if (!keyCreation) {
    keyCreation = createKey().catch(() => { keyCreation = null; throw new Error('Cannot create protected pairing key'); });
  }
  await keyCreation;
}

async function createKey(): Promise<void> {
  if (await huks.isKeyItemExist(KEY_ALIAS, {})) return;
  const options: huks.HuksOptions = {};
  options.properties = properties(huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT | huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT);
  await huks.generateKeyItem(KEY_ALIAS, options);
}

function properties(purpose: number): huks.HuksParam[] {
  return [
    { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
    { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: 256 },
    { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: purpose },
    { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
    { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
  ];
}

async function operate(encrypt: boolean, iv: Uint8Array, input: Uint8Array): Promise<Uint8Array> {
  if (iv.length !== 12 || input.length > 64 * 1024 || (!encrypt && input.length < TAG_BYTES)) {
    throw new Error('Invalid protected pairing data');
  }
  const params = properties(encrypt ? huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT : huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT);
  params.push({ tag: huks.HuksTag.HUKS_TAG_NONCE, value: iv });
  if (!encrypt) params.push({ tag: huks.HuksTag.HUKS_TAG_AE_TAG, value: input.slice(input.length - TAG_BYTES) });
  const options: huks.HuksOptions = {};
  options.properties = params;
  const session = await huks.initSession(KEY_ALIAS, options);
  let finished = false;
  try {
    options.inData = encrypt ? input : input.subarray(0, input.length - TAG_BYTES);
    const result = await huks.finishSession(session.handle, options);
    finished = true;
    if (!result.outData || result.outData.length !== (encrypt ? input.length + TAG_BYTES : input.length - TAG_BYTES)) {
      throw new Error('Invalid protected pairing output');
    }
    return result.outData;
  } finally {
    if (!finished) {
      try { await huks.abortSession(session.handle, {}); } catch (error) { /* The service may already have released it. */ }
    }
  }
}
