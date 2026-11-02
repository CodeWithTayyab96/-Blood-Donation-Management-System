package com.tayyab.blooddonation.util

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Entry point for Firebase Cloud Messaging, declared in the manifest.
 *
 * The app only uses data-only messages: [onMessageReceived] hands the payload
 * to [PushHandler], which builds the notification itself so the same dedup
 * rules and channels apply whether an alert arrives by push or by the
 * 15-minute poll fallback. [onNewToken] re-uploads the token so the
 * `device_tokens` row follows Firebase rotations.
 *
 * Firebase calls both callbacks on a background thread; [PushHandler] and
 * [FcmPush.refreshToken] document that blocking I/O is fine there.
 * [FcmPush.refreshToken] is a no-op when Firebase was not configured in this
 * build, so this service is harmless without the Firebase values.
 */
class BloodMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        PushHandler.handle(this, message.data)
    }

    override fun onNewToken(token: String) {
        // The token changed: make sure the server row follows. refreshToken
        // re-reads the current token and only upserts when it changed.
        Background.run { FcmPush.refreshToken(applicationContext) }
    }
}
