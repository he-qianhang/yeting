import { BotGuardClient } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import { getHeaders, parseLooseJSON } from 'bgutils-js/utils';

// Network requests stay in Kotlin. This document has no native bridge or network access.
let signals;
window.proofHeaders = getHeaders();
window.proofState = { status: 'ready' };
window.bootstrapProof = (config, attestation) => {
    window.yt = { config_: config };
    const data = parseLooseJSON(attestation);
    return {
        visitor: config.INNERTUBE_CONTEXT?.client?.visitorData || config.VISITOR_DATA,
        challenge: data.R?.bgChallenge
    };
};
window.snapshotProof = async raw => {
    window.proofState = { status: 'working' };
    try {
        const challenge = raw;
        const script = challenge.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue;
        if (!script) throw new Error();
        new Function(script)();
        const client = await BotGuardClient.create({
            program: challenge.program, globalName: challenge.globalName, globalObject: globalThis
        });
        signals = [];
        const response = await client.snapshot({ webPoSignalOutput: signals });
        window.proofState = { status: 'done', response };
    } catch {
        window.proofState = { status: 'error' };
    }
};
window.mintProof = async (raw, video) => {
    window.proofState = { status: 'working' };
    try {
        const minter = await WebPoMinter.create({ integrityToken: raw[0] }, signals);
        window.proofState = {
            status: 'done',
            player: await minter.mintAsWebsafeString(video),
            content: await minter.mintAsWebsafeString(video)
        };
    } catch (error) {
        const known = ['PMD:Undefined', 'APF:Failed', 'YNJ:Undefined', 'ODM:Invalid', 'No integrity token provided'];
        window.proofState = { status: 'error', reason: known.includes(error.message) ? error.message : 'unknown' };
    }
};
