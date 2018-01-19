/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.participants.views;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.waz.api.CoreList;
import com.waz.api.UpdateListener;
import com.waz.api.User;
import com.waz.api.Verification;
import com.waz.model.UserId;
import com.waz.zclient.R;
import com.waz.zclient.common.views.ChatheadWithTextFooter;
import com.waz.zclient.utils.ViewUtils;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class ParticipantsChatheadAdapter extends BaseAdapter implements UpdateListener {
    private static final int VIEW_TYPE_COUNT = 3;
    public static final int VIEW_TYPE_CHATHEAD = 0;
    public static final int VIEW_TYPE_SEPARATOR_VERIFIED = 1;
    public static final int VIEW_TYPE_EMPTY = 2;
    public static final int VIEW_TYPE_SEPARATOR_BOTS = 3;

    private CoreList<User> usersList;
    private int numOfColumns;

    private List<User> userListVerified = new ArrayList<>();
    private List<User> userListUnverified = new ArrayList<>();
    private List<User> userListBots = new ArrayList<>();

    private int separatorPosVerified = -1;
    private int separatorPosBots = -1;

    public void setUsersList(CoreList<User> usersList, int numOfColumns) {
        this.numOfColumns = numOfColumns;

        if (this.usersList != null) {
            this.usersList.removeUpdateListener(this);
        }

        this.usersList = usersList;
        if (this.usersList != null) {
            this.usersList.addUpdateListener(this);
            for (User user : usersList) {
                user.addUpdateListener(this);
            }
        }
        updated();
    }

    public void tearDown() {
        if (usersList != null) {
            for (User user : usersList) {
                user.removeUpdateListener(this);
            }
            usersList.removeUpdateListener(this);
        }
    }

    @Override
    public int getCount() {
        // Hack to make it overscrollable
        if (userListUnverified.isEmpty() && userListVerified.isEmpty() && userListBots.isEmpty()) {
            Timber.i("getCount: %d", 1);
            return 1;
        }

        int count = userListVerified.size() + userListUnverified.size() + userListBots.size();

        if (!userListUnverified.isEmpty() && separatorPosVerified != -1) {
            count += numOfColumns;
        }

        if (separatorPosBots != -1) {
            count += numOfColumns;
        }

        Timber.i("getCount: %d", count);

        return count;
    }

    @Override
    public User getItem(int position) {
        Timber.i("trying to get item: %d", position);
        if (position < userListUnverified.size()) {
            Timber.i("userListUnverified.size: %d so that's it", userListUnverified.size());
            return userListUnverified.get(position);
        }

        position -= userListUnverified.size();

        if (position < numOfColumns) {
            Timber.i("the user apparently tapped on the empty space between unverified and verified");
            return null;
        }

        position -= numOfColumns;

        if (separatorPosVerified != -1) {
            if (position < userListVerified.size()) {
                Timber.i("userListVerified.size: %d so that's it", userListVerified.size());
                return userListVerified.get(position);
            }

            position -= userListVerified.size();


            if (position < numOfColumns) {
                Timber.i("the user apparently tapped on the empty space between verified and bots");
                return null;
            }

            position -= numOfColumns;
        }

        if (position < userListBots.size()) {
            Timber.i("userListBots.size: %d so that's it", userListBots.size());
            return userListBots.get(position);
        }

        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return VIEW_TYPE_COUNT;
    }

    @Override
    public int getItemViewType(int position) {
        if (position < userListUnverified.size() ||
            (position >= separatorPosVerified + numOfColumns && position < separatorPosBots) ||
            (position >= separatorPosBots + numOfColumns && position < separatorPosBots + numOfColumns + userListBots.size())
            ) {
            return VIEW_TYPE_CHATHEAD;
        }

        if (position == separatorPosVerified) {
            return VIEW_TYPE_SEPARATOR_VERIFIED;
        }

        if (position == separatorPosBots) {
            return VIEW_TYPE_SEPARATOR_BOTS;
        }

        return VIEW_TYPE_EMPTY;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_CHATHEAD:
                return getChatheadLabel(position, convertView, parent);
            case VIEW_TYPE_SEPARATOR_VERIFIED:
                return getSeparatorView(parent, false);
            case VIEW_TYPE_SEPARATOR_BOTS:
                return getSeparatorView(parent, true);
            default:
            case VIEW_TYPE_EMPTY:
                View view = new View(parent.getContext());
                view.setLayoutParams(new AbsListView.LayoutParams(0,
                        parent.getResources().getDimensionPixelSize(R.dimen.participants__verified_row__height)));
                view.setVisibility(View.GONE);
                return view;

        }
    }

    private View getChatheadLabel(int position, View convertView, ViewGroup parent) {
        ChatheadWithTextFooter view;
        if (convertView == null) {
            view = new ChatheadWithTextFooter(parent.getContext());
        } else {
            view = (ChatheadWithTextFooter) convertView;
        }
        User user = getItem(position);
        if (user != null) {
            view.setUserId(new UserId(user.getId()));
            view.setVisibility(View.VISIBLE);
        } else {
            //TODO https://wearezeta.atlassian.net/browse/AN-4276
            view.setVisibility(View.INVISIBLE);
        }
        return view;
    }

    public View getSeparatorView(ViewGroup parent, boolean isBotSeparator) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(R.layout.participants_separator_row, parent, false);
        view.setLayoutParams(new AbsListView.LayoutParams(parent.getMeasuredWidth(),
                parent.getResources().getDimensionPixelSize(R.dimen.participants__verified_row__height)));

        TextView separatorTitleView = ViewUtils.getView(view, R.id.separator_title);
        if (isBotSeparator) {
            separatorTitleView.setText(R.string.integrations_picker__section_title);
        } else {
            separatorTitleView.setText(R.string.pref_devices_device_verified);
        }

        return view;
    }

    @Override
    public void updated() {
        userListVerified.clear();
        userListUnverified.clear();
        userListBots.clear();
        if (usersList != null) {
            for (User user : usersList) {
                Timber.i("user %s is a bot: %b, and verified: %s", user.getDisplayName(),  user.isBot(), user.getVerified().toString());
                if (user.getVerified() == Verification.VERIFIED) {
                    userListVerified.add(user);
                } else if (user.isBot()) {
                    userListBots.add(user);
                } else {
                    userListUnverified.add(user);
                }
            }
        }

        // fill up with empty spaces
        fillWithEmptySpaces(userListUnverified);
        fillWithEmptySpaces(userListVerified);

        if (!userListVerified.isEmpty()) {
            separatorPosVerified = userListUnverified.size();
        } else {
            separatorPosVerified = -1;
        }

        if (!userListBots.isEmpty()) {
            separatorPosBots = userListUnverified.size() + userListVerified.size();
        } else {
            separatorPosBots = -1;
        }

        notifyDataSetChanged();
    }

    private void fillWithEmptySpaces(List<User> list) {
        if (!list.isEmpty()) {
            int rest = list.size() % numOfColumns;
            if (rest != 0) {
                int fillupCount = numOfColumns - rest;
                for (int i = 0; i < fillupCount; i++) {
                    list.add(null);
                }
            }
        }
    }
}
