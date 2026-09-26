package com.vpsmanager.data.push

import com.vpsmanager.mobileapiclient.api.MobileApi
import com.vpsmanager.mobileapiclient.infrastructure.ClientException
import com.vpsmanager.mobileapiclient.infrastructure.ServerException
import com.vpsmanager.mobileapiclient.model.RegisterDeviceInputBody
import java.io.IOException

/** Outcome of registering/unregistering this device for push (`/api/mobile/v1/notify/devices`). */
sealed interface PushDeviceResult {
    data object Success : PushDeviceResult
    data class Error(val reason: String) : PushDeviceResult
}

/**
 * The single call site into the generated mobile BFF client (`:data:mobile-api-client`) for
 * FCM device registration — mirrors `com.vpsmanager.data.terminal.TerminalRepository`'s exact
 * shape. Calls the real, only device-registration endpoints
 * (`POST`/`DELETE /api/mobile/v1/notify/devices`) — never the non-existent
 * `/api/mobile/v1/devices/register` an earlier draft cited.
 */
class PushDeviceRepository(
    private val mobileApi: MobileApi = MobileApi(),
) {
    suspend fun register(deviceId: String, fcmToken: String, platform: String = "android"): PushDeviceResult = try {
        mobileApi.registerPushDevice(
            RegisterDeviceInputBody(deviceId = deviceId, fcmToken = fcmToken, platform = platform),
        )
        PushDeviceResult.Success
    } catch (e: ClientException) {
        PushDeviceResult.Error("Não foi possível registrar este dispositivo para notificações (erro ${e.statusCode}).")
    } catch (e: ServerException) {
        PushDeviceResult.Error("O servidor está indisponível no momento.")
    } catch (e: IOException) {
        PushDeviceResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        PushDeviceResult.Error("Erro de configuração ao registrar o dispositivo.")
    } catch (e: UnsupportedOperationException) {
        PushDeviceResult.Error("Resposta inesperada do servidor.")
    } catch (e: Exception) {
        PushDeviceResult.Error("Não foi possível registrar este dispositivo.")
    }

    suspend fun unregister(deviceId: String): PushDeviceResult = try {
        mobileApi.unregisterPushDevice(deviceId)
        PushDeviceResult.Success
    } catch (e: ClientException) {
        PushDeviceResult.Error("Não foi possível remover este dispositivo (erro ${e.statusCode}).")
    } catch (e: ServerException) {
        PushDeviceResult.Error("O servidor está indisponível no momento.")
    } catch (e: IOException) {
        PushDeviceResult.Error("Falha de conexão. Verifique a rede e tente novamente.")
    } catch (e: IllegalStateException) {
        PushDeviceResult.Error("Erro de configuração ao remover o dispositivo.")
    } catch (e: UnsupportedOperationException) {
        PushDeviceResult.Error("Resposta inesperada do servidor.")
    } catch (e: Exception) {
        PushDeviceResult.Error("Não foi possível remover este dispositivo.")
    }
}
