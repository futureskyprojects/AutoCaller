package vn.vistark.autocaller.ui.campaign_detail

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.pedant.SweetAlert.SweetAlertDialog
import es.dmoral.toasty.Toasty
import kotlinx.android.synthetic.main._campaign_item.*
import kotlinx.android.synthetic.main.activity_campaign_create.campaignItemName
import kotlinx.android.synthetic.main.activity_campaign_create.campaignItemProgressBar
import kotlinx.android.synthetic.main.activity_campaign_create.campaignItemProgressCount
import kotlinx.android.synthetic.main.activity_campaign_create.campaignItemProgressPercent
import kotlinx.android.synthetic.main.activity_campaign_detail.*
import vn.vistark.autocaller.R
import vn.vistark.autocaller.controller.campaign_detail.CampaignCall
import vn.vistark.autocaller.controller.campaign_detail.CampaignResetLoader
import vn.vistark.autocaller.models.CampaignDataModel
import vn.vistark.autocaller.models.CampaignModel
import vn.vistark.autocaller.models.repositories.CampaignDataRepository
import vn.vistark.autocaller.models.repositories.CampaignRepository
import vn.vistark.autocaller.services.BackgroundService
import vn.vistark.autocaller.services.BackgroundService.Companion.IsBackgroundServiceRunning
import vn.vistark.autocaller.services.BackgroundService.Companion.StartBackgroundService
import vn.vistark.autocaller.services.BackgroundService.Companion.StopBackgroundService
import vn.vistark.autocaller.services.BackgroundService.Companion.isStartCampaign
import vn.vistark.autocaller.services.BackgroundService.Companion.isStopTemporarily
import vn.vistark.autocaller.utils.call_phone.PhoneStateReceiver
import vn.vistark.autocaller.ui.campaign.CampaignActivity
import vn.vistark.autocaller.ui.campaign_update.CampaignUpdateActivity
import java.lang.Exception
import kotlin.collections.ArrayList


class CampaignDetailActivity : AppCompatActivity() {

    var campaign: CampaignModel? = null

    val campaignDatas = ArrayList<CampaignDataModel>()
    lateinit var adapter: CampaignDataDetailAdapter

    var lastPhoneIndex = 0

    lateinit var campaignDataRepository: CampaignDataRepository


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_campaign_detail)

        // Thiết lập răng đây là lần đầu cho cuộc gọi
        PhoneStateReceiver.isFirstTime = true

        // Thiết lập tiêu đề
        supportActionBar?.title = "Thông tin chiến dịch"

        // Hiển thị nút trở về
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Khởi tạo lấy thông tin chiến dịch
        initCampaignData()

        // Lấy kho chứa số điện thoại
        campaignDataRepository = CampaignDataRepository(this)

        // Thiết lập adapter
        adapter = CampaignDataDetailAdapter(campaignDatas)

        // Thiết lập recycler view danh sách
        cDataRvListPhone.layoutManager = LinearLayoutManager(this)
        cDataRvListPhone.setHasFixedSize(true)
        cDataRvListPhone.adapter = adapter

        // Gọi sự kiện khi kéo đến gần cuối danh sách
        initLoadMoreEvents()

        // Tắt nút tạm ngưng
        acdBtnPause.isEnabled = false
        acdBtnStart.isEnabled =
            campaign != null && campaign!!.totalCalled != campaign!!.totalImported

        // Sự kiện khi nhấn nút bắt đầu
        startBtnEvent()

        // Sự kiện khi nhấn nút tạm ngưng
        pauseBtnEvent()

        // Sự kiện khi nhấn chỉnh sửa chiến dịch
        editBtnEvent()

        // Load 200 record đầu
        loadMore()
    }

    private fun editBtnEvent() {
        cItemLnRoot.setOnClickListener {
            campaignTvOption.performClick()
        }
        campaignTvOption.setOnClickListener {
            // Tạm ngưng việc chạy chiến dịch để thực hiện việc chỉnh sửa
            if (acdBtnPause.isEnabled)
                acdBtnPause.performClick()

            // Khởi chạy trang chỉnh sửa
            val intent = Intent(this, CampaignUpdateActivity::class.java)
            intent.putExtra(CampaignModel.ID, campaign?.id ?: -1)
            startActivity(intent)

            // Kết thúc trang thông tin hiện tại cho nhẹ
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_top_right_campaign_detail, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.trMenuReloadCampaign -> {
                return confirmResetCampaign()
            }
            android.R.id.home -> {
                onBackPressed()
            }
            else -> {
                Toasty.error(this, "Không tìm thấy tùy chọn này", Toasty.LENGTH_SHORT, true).show()
            }
        }
        return false
    }

    private fun confirmResetCampaign(): Boolean {
        if (IsBackgroundServiceRunning()) {
            Toasty.error(
                this,
                "Vui lòng TẠM NGƯNG chiến dịch trước khi thao tác",
                Toasty.LENGTH_SHORT
            ).show()
            return true
        }
        SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
            .setTitleText("Khi thực hiện thao tác này toàn bộ dữ liệu cuộc gọi sẽ được làm mới và trở về trạng thái chưa gọi và bạn không thể hoàn tác. Bạn có chắc muốn thiết lập lại?")
            .setContentText("THIẾT LẬP LẠI")
            .showCancelButton(true)
            .setCancelButton("Hủy thao tác") {
                it.dismissWithAnimation()
                it.cancel()
            }
            .setConfirmButton("Xác nhận") {
                it.dismissWithAnimation()
                it.cancel()
                CampaignResetLoader(this)
            }.show()
        return true
    }

    private fun stopRegisReciver() {
        try {
            unregisterReceiver(BackgroundService.broadcastReceiver)
        } catch (e: Exception) {
        }
    }

    fun pause(isLoadMore: Boolean = true) {
        isStartCampaign = false
        // Bỏ đăng ký nghe khi xong cuộc gọi
        stopRegisReciver()

        StopBackgroundService()

        // Điều chỉnh view
        runOnUiThread {
            acdBtnPause.isEnabled = false
            acdBtnStart.isEnabled = true

            cDataRvListPhone.visibility = View.VISIBLE
            acdRlCallShowing.visibility = View.GONE
        }

        // Nếu cho phép load thêm dữ liệu (Mặc định)
        if (isLoadMore)
            loadMore()
    }

    private fun pauseBtnEvent() {
        acdBtnPause.setOnClickListener {
            SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
                .setTitleText("Bạn có thực sự muốn dừng chiến dịch hiện tại?")
                .setContentText("DỪNG CHIẾN DỊCH")
                .setCancelText("Quay lại")
                .setConfirmText("Dừng ngay")
                .showCancelButton(true)
                .setCancelClickListener { sDialog -> sDialog.cancel() }
                .setConfirmClickListener { sDialog ->
                    sDialog.dismissWithAnimation()
                    sDialog.cancel()
                    pause()
                }
                .show()
        }
    }

    private fun startBtnEvent() {
        acdBtnStart.setOnClickListener {

            StartBackgroundService(campaign!!)

            acdBtnPause.isEnabled = true
            acdBtnStart.isEnabled = false

            cDataRvListPhone.visibility = View.GONE
            acdRlCallShowing.visibility = View.VISIBLE
            lastPhoneIndex = 0
            campaignDatas.clear()
            adapter.notifyDataSetChanged()

        }
    }

    override fun onStart() {
        super.onStart()
        CampaignCall.act = this
    }

    @SuppressLint("SetTextI18n")
    fun initCampaignData() {
        var campaignId = 0
        if (campaign == null) {
            campaignId = intent.getIntExtra(CampaignModel.ID, -1)
            if (campaignId <= 0) {
                Toasty.error(
                    this,
                    "Không thể xác định được chiến dịch cần xem thông tin",
                    Toasty.LENGTH_SHORT,
                    true
                ).show()
                onBackPressed()
                return
            }
        } else
            campaignId = campaign!!.id

        campaign = CampaignRepository(this).get(campaignId)

        if (campaign == null) {
            Toasty.error(
                this,
                "Không thể lấy chiến dịch cần xem thông tin",
                Toasty.LENGTH_SHORT,
                true
            ).show()
            onBackPressed()
            return
        }

        // Hiển thị
        campaignItemName.text = campaign!!.name
        campaignItemProgressCount.text =
            "(${campaign!!.totalCalled}/${campaign!!.totalImported})"

        val progress: Int =
            ((campaign!!.totalCalled.toDouble() / campaign!!.totalImported.toDouble()) * 100).toInt()
        campaignItemProgressBar.progress = progress
        campaignItemProgressPercent.text = "$progress%"
    }

    private fun initLoadMoreEvents() {
        cDataRvListPhone.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!recyclerView.canScrollVertically(1)) {
                    loadMore()
                }
            }
        })
    }

    private fun loadMore() {
        val phones = campaignDataRepository.getLimit(campaign!!.id, lastPhoneIndex, 100)

        if (phones.isEmpty())
            return

        lastPhoneIndex = phones[phones.size - 1].id
        campaignDatas.addAll(phones)
        runOnUiThread {
            adapter.notifyDataSetChanged()
        }
    }


    fun goBack() {
        pause(false)
        val intent = Intent(this, CampaignActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (isStartCampaign || isStopTemporarily) {
            SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
                .setTitleText("Chiến dịch đang diễn ra, bạn có thực sự muốn tạm ngưng và quay về?")
                .setContentText("VỀ DANH SÁCH")
                .showCancelButton(true)
                .setCancelButton("Không") {
                    it.dismissWithAnimation()
                    it.cancel()
                }
                .setConfirmButton("Đồng ý") {
                    it.dismissWithAnimation()
                    it.cancel()
                    goBack()
                }.show()
        } else {
            goBack()
        }
    }

    // Khi nhấn nút trở về
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return super.onSupportNavigateUp()
    }

    fun startCallFail() {
        SweetAlertDialog(this, SweetAlertDialog.ERROR_TYPE)
            .setTitleText("Khởi động cuộc gọi không thành công")
            .setContentText("KHÔNG THÀNH CÔNG")
            .showCancelButton(true)
            .setCancelButton("Đóng") { d ->
                d.dismissWithAnimation()
                d.cancel()
            }.show()
        pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        CampaignCall.act = null
    }

    fun successCallAllPhone() {
        pause()
        acdBtnPause.post {
            acdBtnPause.isEnabled = false
            acdBtnStart.isEnabled = false
            SweetAlertDialog(this, SweetAlertDialog.SUCCESS_TYPE)
                .setTitleText("Hoàn tất chiến dịch")
                .setContentText("CHÚC MỪNG")
                .setConfirmButton("Xác nhận") {
                    it.dismissWithAnimation()
                    it.cancel()
                }.show()
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateCallingInfo(callingPhone: CampaignDataModel) {
        // Cập nhật số đang gọi và thứ tự
        acdCallingPhoneIndex.post {
            acdCallingPhoneIndex.text = "ĐANG GỌI SỐ THỨ ${callingPhone.indexInCampaign + 1}"
            acdCallingPhoneNumber.text = callingPhone.phone ?: "<Không xác định>"
        }
    }
}