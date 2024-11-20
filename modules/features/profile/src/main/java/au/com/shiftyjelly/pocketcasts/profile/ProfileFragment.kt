package au.com.shiftyjelly.pocketcasts.profile

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.endofyear.StoriesActivity.StoriesSource
import au.com.shiftyjelly.pocketcasts.endofyear.ui.EndOfYearPromptCard
import au.com.shiftyjelly.pocketcasts.localization.extensions.getStringPluralSecondsMinutesHoursDaysOrYears
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.models.type.SubscriptionTier
import au.com.shiftyjelly.pocketcasts.player.view.bookmark.BookmarksContainerFragment
import au.com.shiftyjelly.pocketcasts.podcasts.view.ProfileEpisodeListFragment
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.profile.cloud.CloudFilesFragment
import au.com.shiftyjelly.pocketcasts.profile.databinding.FragmentProfileBinding
import au.com.shiftyjelly.pocketcasts.referrals.ReferralsClaimGuestPassBannerCard
import au.com.shiftyjelly.pocketcasts.referrals.ReferralsIconWithTooltip
import au.com.shiftyjelly.pocketcasts.repositories.endofyear.EndOfYearManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import au.com.shiftyjelly.pocketcasts.settings.HelpFragment
import au.com.shiftyjelly.pocketcasts.settings.SettingsFragment
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingFlow
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingLauncher
import au.com.shiftyjelly.pocketcasts.settings.onboarding.OnboardingUpgradeSource
import au.com.shiftyjelly.pocketcasts.settings.stats.StatsFragment
import au.com.shiftyjelly.pocketcasts.ui.extensions.getThemeColor
import au.com.shiftyjelly.pocketcasts.ui.extensions.getThemeTintedDrawable
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import au.com.shiftyjelly.pocketcasts.utils.extensions.dpToPx
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import com.google.android.material.badge.ExperimentalBadgeUtils
import dagger.hilt.android.AndroidEntryPoint
import java.util.Date
import javax.inject.Inject
import kotlin.time.Duration
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR
import au.com.shiftyjelly.pocketcasts.ui.R as UR

@AndroidEntryPoint
class ProfileFragment : BaseFragment() {

    @Inject
    lateinit var podcastManager: PodcastManager

    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var analyticsTracker: AnalyticsTracker

    private val viewModel: ProfileViewModel by viewModels()

    private var binding: FragmentProfileBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.clearFailedRefresh()
    }

    override fun onPause() {
        super.onPause()
        viewModel.isFragmentChangingConfigurations = activity?.isChangingConfigurations ?: false
    }

    @OptIn(ExperimentalBadgeUtils::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = binding ?: return

        binding.btnSettings.setOnClickListener {
            analyticsTracker.track(AnalyticsEvent.PROFILE_SETTINGS_BUTTON_TAPPED)
            (activity as FragmentHostListener).addFragment(SettingsFragment())
        }

        binding.setupProfileHeader()
        binding.setupStatsView()
        binding.setupReferralsClaimGuestPassCard()
        binding.setupEndOfYearPromptCard()
        binding.setupSections()

        viewModel.signInState.observe(viewLifecycleOwner) { state ->
            binding.upgradeLayout.root.isInvisible = settings.getUpgradeClosedProfile() || state.isSignedInAsPlusOrPatron
            if (binding.upgradeLayout.root.isInvisible) {
                // We need this to get the correct padding below refresh
                binding.upgradeLayout.root.updateLayoutParams<ConstraintLayout.LayoutParams> { height = 16.dpToPx(view.context) }
            }
        }

        if (FeatureFlag.isEnabled(Feature.REFERRALS_SEND)) {
            binding.btnGift.setContent {
                AppTheme(theme.activeTheme) {
                    ReferralsIconWithTooltip()
                }
            }
        }

        binding.btnRefresh.setOnClickListener {
            updateRefreshUI(RefreshState.Refreshing)
            podcastManager.refreshPodcasts("profile")
            analyticsTracker.track(AnalyticsEvent.PROFILE_REFRESH_BUTTON_TAPPED)
        }

        val upgradeLayout = binding.upgradeLayout
        upgradeLayout.btnClose.setOnClickListener {
            settings.setUpgradeClosedProfile(true)
            upgradeLayout.root.isVisible = false
        }

        upgradeLayout.lblGetMore.text = getString(LR.string.profile_help_support)
        upgradeLayout.root.setOnClickListener {
            OnboardingLauncher.openOnboardingFlow(
                activity = activity,
                onboardingFlow = OnboardingFlow.Upsell(OnboardingUpgradeSource.PROFILE),
            )
        }

        viewModel.refreshObservable.observe(viewLifecycleOwner) { state ->
            updateRefreshUI(state)
        }

        if (!viewModel.isFragmentChangingConfigurations) {
            analyticsTracker.track(AnalyticsEvent.PROFILE_SHOWN)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.bottomInset.collect {
                    view.updatePadding(bottom = it)
                }
            }
        }
    }

    private fun onProfileAccountButtonClicked() {
        analyticsTracker.track(AnalyticsEvent.PROFILE_ACCOUNT_BUTTON_TAPPED)
        if (viewModel.isSignedIn) {
            val fragment = AccountDetailsFragment.newInstance()
            (activity as FragmentHostListener).addFragment(fragment)
        } else {
            OnboardingLauncher.openOnboardingFlow(activity, OnboardingFlow.LoggedOut)
        }
    }

    private fun FragmentProfileBinding.setupEndOfYearPromptCard() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (viewModel.isEndOfYearStoriesEligible()) {
                    endOfYearPromptCard.setContent {
                        AppTheme(theme.activeTheme) {
                            EndOfYearPromptCard(
                                onClick = {
                                    analyticsTracker.track(
                                        AnalyticsEvent.END_OF_YEAR_PROFILE_CARD_TAPPED,
                                        mapOf("year" to EndOfYearManager.YEAR_TO_SYNC.value),
                                    )
                                    // once stories prompt card is tapped, we don't want to show stories launch modal if not already shown
                                    if (settings.getEndOfYearShowModal()) {
                                        settings.setEndOfYearShowModal(false)
                                    }
                                    (activity as? FragmentHostListener)?.showStoriesOrAccount(StoriesSource.PROFILE.value)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun FragmentProfileBinding.setupReferralsClaimGuestPassCard() {
        referralsClaimGuestPassBannerCard.setContent {
            AppTheme(theme.activeTheme) {
                ReferralsClaimGuestPassBannerCard()
            }
        }
    }

    private fun FragmentProfileBinding.setupProfileHeader() {
        userView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        userView.setContent {
            val headerState by viewModel.profileHeaderState
                .collectAsStateWithLifecycle(
                    ProfileHeaderState(
                        imageUrl = null,
                        subscriptionTier = SubscriptionTier.NONE,
                        email = null,
                        expiresIn = null,
                    ),
                )

            AppTheme(remember { theme.activeTheme }) {
                ProfileHeader(
                    state = headerState,
                    onClick = ::onProfileAccountButtonClicked,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun FragmentProfileBinding.setupStatsView() {
        statsView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        statsView.setContent {
            val headerState by viewModel.profileStatsState
                .collectAsStateWithLifecycle(
                    ProfileStatsState(
                        podcastsCount = 0,
                        listenedDuration = Duration.ZERO,
                        savedDuration = Duration.ZERO,
                    ),
                )

            AppTheme(remember { theme.activeTheme }) {
                ProfileStats(
                    state = headerState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun FragmentProfileBinding.setupSections() {
        sectionsView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        sectionsView.setContent {
            AppTheme(remember { theme.activeTheme }) {
                ProfileSections(
                    sections = ProfileSection.entries,
                    onClick = ::goToSection,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    private fun goToSection(section: ProfileSection) {
        val fragment = when (section) {
            ProfileSection.Stats -> {
                analyticsTracker.track(AnalyticsEvent.STATS_SHOWN)
                StatsFragment()
            }
            ProfileSection.Downloads -> {
                analyticsTracker.track(AnalyticsEvent.DOWNLOADS_SHOWN)
                ProfileEpisodeListFragment.newInstance(ProfileEpisodeListFragment.Mode.Downloaded)
            }
            ProfileSection.CloudFiles -> {
                analyticsTracker.track(AnalyticsEvent.UPLOADED_FILES_SHOWN)
                CloudFilesFragment()
            }
            ProfileSection.Starred -> {
                analyticsTracker.track(AnalyticsEvent.STARRED_SHOWN)
                ProfileEpisodeListFragment.newInstance(ProfileEpisodeListFragment.Mode.Starred)
            }
            ProfileSection.Bookmarks -> {
                analyticsTracker.track(AnalyticsEvent.PROFILE_BOOKMARKS_SHOWN)
                BookmarksContainerFragment.newInstance(sourceView = SourceView.PROFILE)
            }
            ProfileSection.ListeningHistory -> {
                analyticsTracker.track(AnalyticsEvent.LISTENING_HISTORY_SHOWN)
                ProfileEpisodeListFragment.newInstance(ProfileEpisodeListFragment.Mode.History)
            }
            ProfileSection.Help -> {
                analyticsTracker.track(AnalyticsEvent.SETTINGS_HELP_SHOWN)
                HelpFragment()
            }
        }
        (requireActivity() as? FragmentHostListener)?.addFragment(fragment)
    }

    private fun updateRefreshUI(state: RefreshState?) {
        val binding = binding ?: return
        val lblRefreshStatus = binding.lblRefreshStatus
        when (state) {
            is RefreshState.Never -> {
                lblRefreshStatus.text = getString(LR.string.profile_refreshed_never)
                lblRefreshStatus.setCompoundDrawables(null, null, null, null)
                lblRefreshStatus.setOnClickListener(null)
            }

            is RefreshState.Success -> {
                updateLastRefreshText(lblRefreshStatus, state.date)
                lblRefreshStatus.setCompoundDrawables(null, null, null, null)
                lblRefreshStatus.setOnClickListener(null)
            }

            is RefreshState.Refreshing -> {
                lblRefreshStatus.text = getString(LR.string.profile_refreshing)
                lblRefreshStatus.setCompoundDrawables(null, null, null, null)
                lblRefreshStatus.setOnClickListener(null)
            }

            is RefreshState.Failed -> {
                lblRefreshStatus.text = getString(LR.string.profile_refresh_failed)
                context?.let { context ->
                    val errorDrawable = context.getThemeTintedDrawable(IR.drawable.ic_alert_small, UR.attr.primary_icon_02)
                    lblRefreshStatus.setCompoundDrawablesWithIntrinsicBounds(errorDrawable, null, null, null)
                    lblRefreshStatus.compoundDrawablePadding = 8.dpToPx(context)
                    TextViewCompat.setCompoundDrawableTintList(
                        lblRefreshStatus,
                        ColorStateList.valueOf(
                            context.getThemeColor(
                                UR.attr.secondary_icon_01,
                            ),
                        ),
                    )
                    lblRefreshStatus.setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(LR.string.profile_refresh_error)
                            .setMessage(state.error)
                            .setPositiveButton(LR.string.ok, null)
                            .show()
                    }
                }
            }

            else -> {
                lblRefreshStatus.setText(LR.string.profile_refresh_status_unknown)
                lblRefreshStatus.setCompoundDrawables(null, null, null, null)
                lblRefreshStatus.setOnClickListener(null)
            }
        }
    }

    private fun updateLastRefreshText(lblRefreshStatus: TextView, lastRefresh: Date) {
        val time = Date().time - lastRefresh.time
        val timeAmount = resources.getStringPluralSecondsMinutesHoursDaysOrYears(time)
        lblRefreshStatus.text = getString(LR.string.profile_last_refresh, timeAmount)
    }

    override fun onBackPressed(): Boolean {
        viewModel.refreshStats()
        return super.onBackPressed()
    }
}
