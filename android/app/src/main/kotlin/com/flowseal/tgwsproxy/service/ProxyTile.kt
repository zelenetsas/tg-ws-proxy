package com.flowseal.tgwsproxy.service

import android.service.quicksettings.TileService

/**
 * Quick Settings Tile: вкл/выкл прокси из шторки.
 * Аналог пунктов «Запустить/Стоп» меню трея десктопной версии.
 */
class ProxyTile : TileService() {

    override fun onClick() {
        super.onClick()
        val running = ProxyService.state.value is ProxyState.Running ||
            ProxyService.state.value is ProxyState.Starting
        ProxyLauncher.send(this, if (running) ProxyService.ACTION_STOP else ProxyService.ACTION_START)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = ProxyService.state.value is ProxyState.Running
        tile.state = if (active) {
            android.service.quicksettings.Tile.STATE_ACTIVE
        } else {
            android.service.quicksettings.Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
