package com.bloemstudios.admin.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertPermissionTest {
    @Test fun androidThirteenWithoutThePermissionAsksFirst() {
        assertEquals(EnableAlertsStep.AskPermission, stepToEnableAlerts(sdkInt = 33, permissionGranted = false))
        assertEquals(EnableAlertsStep.AskPermission, stepToEnableAlerts(sdkInt = 36, permissionGranted = false))
    }

    @Test fun androidThirteenWithThePermissionTurnsOnAtOnce() {
        assertEquals(EnableAlertsStep.TurnOn, stepToEnableAlerts(sdkInt = 33, permissionGranted = true))
    }

    @Test fun olderAndroidHasNothingToAsk() {
        assertEquals(EnableAlertsStep.TurnOn, stepToEnableAlerts(sdkInt = 32, permissionGranted = false))
        assertEquals(EnableAlertsStep.TurnOn, stepToEnableAlerts(sdkInt = 26, permissionGranted = false))
    }

    @Test fun aDeniedAnswerKeepsAlertsOffAndExplainsWhy() {
        assertEquals(EnableAlertsStep.StayOff, stepAfterPermissionAnswer(granted = false))
        assertEquals(EnableAlertsStep.TurnOn, stepAfterPermissionAnswer(granted = true))
    }

    @Test fun theStatusLineAppearsOnlyWhenAlertsAreOnButTheSystemBlocksThem() {
        assertEquals(AlertDeliveryStatus.Off, alertDeliveryStatus(enabled = false, systemAllows = false))
        assertEquals(AlertDeliveryStatus.Off, alertDeliveryStatus(enabled = false, systemAllows = true))
        assertEquals(AlertDeliveryStatus.On, alertDeliveryStatus(enabled = true, systemAllows = true))
        assertEquals(AlertDeliveryStatus.BlockedBySystem, alertDeliveryStatus(enabled = true, systemAllows = false))
    }
}
