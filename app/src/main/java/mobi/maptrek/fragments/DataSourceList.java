/*
 * Copyright 2024 Andrey Novikov
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package mobi.maptrek.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textview.MaterialTextView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Locale;

import mobi.maptrek.DataHolder;
import mobi.maptrek.R;
import mobi.maptrek.data.Track;
import mobi.maptrek.data.source.DataSource;
import mobi.maptrek.data.source.FileDataSource;
import mobi.maptrek.data.source.WaypointDataSource;
import mobi.maptrek.data.source.WaypointDbDataSource;
import mobi.maptrek.databinding.ListWithEmptyViewBinding;
import mobi.maptrek.util.StringFormatter;
import mobi.maptrek.viewmodels.DataSourceViewModel;

public class DataSourceList extends Fragment {
    private static final Logger logger = LoggerFactory.getLogger(DataSourceList.class);

    private int mAccentColor;
    private int mDisabledColor;
    private DataSourceListAdapter mAdapter;
    private FragmentHolder mFragmentHolder;
    private DataHolder mDataHolder;
    private FloatingActionButton mFloatingButton;
    private DataSourceViewModel dataSourceViewModel;
    private ListWithEmptyViewBinding viewBinding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewBinding = ListWithEmptyViewBinding.inflate(inflater, container, false);
        return viewBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dataSourceViewModel = new ViewModelProvider(requireActivity()).get(DataSourceViewModel.class);
        dataSourceViewModel.getNativeTracksState().observe(getViewLifecycleOwner(), nativeTracks -> {
            logger.error("nativeTracks: {}", nativeTracks);
            if (nativeTracks) {
                viewBinding.empty.setText(R.string.msgEmptyTrackList);

                mFloatingButton = mFragmentHolder.enableListActionButton();
                mFloatingButton.setImageResource(R.drawable.ic_record);
                mFloatingButton.setOnClickListener(v -> {
            /*
            CoordinatesInputDialog.Builder builder = new CoordinatesInputDialog.Builder();
            CoordinatesInputDialog coordinatesInput = builder.setCallbacks(DataSourceList.this)
                    .setTitle(R.string.record_track)
                    .create();
            coordinatesInput.show(getParentFragmentManager(), "trackRecord");
             */
                });
            }

        });
        dataSourceViewModel.getDataSourcesState().observe(getViewLifecycleOwner(), dataSources -> {
            mAdapter.submitList(dataSources);
        });

        mAdapter = new DataSourceListAdapter();
        viewBinding.list.setAdapter(mAdapter);
    }

    @Override
    public void onStop() {
        super.onStop();
        mFragmentHolder.disableListActionButton();
        mFloatingButton = null;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mAccentColor = context.getColor(R.color.colorAccent);
        mDisabledColor = context.getColor(R.color.colorPrimary);
        try {
            mDataHolder = (DataHolder) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context + " must implement DataHolder");
        }
        mFragmentHolder = (FragmentHolder) context;
        getParentFragmentManager().addOnBackStackChangedListener(backStackListener);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        getParentFragmentManager().removeOnBackStackChangedListener(backStackListener);
        mFragmentHolder = null;
        mDataHolder = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    final FragmentManager.OnBackStackChangedListener backStackListener = new FragmentManager.OnBackStackChangedListener() {
        @Override
        public void onBackStackChanged() {
            FragmentManager fragmentManager = getParentFragmentManager();
            int count = fragmentManager.getBackStackEntryCount();
            if (count == 0)
                return;
            boolean nativeTracks = Boolean.TRUE.equals(dataSourceViewModel.getNativeTracksState().getValue());
            FragmentManager.BackStackEntry bse = fragmentManager.getBackStackEntryAt(count - 1);
            Fragment fr = fragmentManager.findFragmentByTag(bse.getName());
            if (fr == DataSourceList.this && nativeTracks) // listener is called on first start too
                mFragmentHolder.enableListActionButton();
        }
    };

    public class DataSourceListAdapter extends ListAdapter<DataSource, DataSourceListAdapter.DataSourceViewHolder> {
        protected DataSourceListAdapter() {
            super(DIFF_CALLBACK);
        }

        @NonNull
        @Override
        public DataSourceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_data_source, parent, false);
            return new DataSourceViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull DataSourceViewHolder holder, int position) {
            DataSource dataSource = getItem(position);
            holder.name.setText(dataSource.name);
            Resources resources = getResources();

            int color = mAccentColor;
            if (dataSource instanceof WaypointDbDataSource) {
                int count = ((WaypointDataSource) dataSource).getWaypointsCount();
                holder.description.setText(resources.getQuantityString(R.plurals.placesCount, count, count));
                holder.icon.setImageResource(R.drawable.ic_points);
                holder.action.setVisibility(View.GONE);
                holder.action.setOnClickListener(null);
            } else {
                boolean nativeTracks = Boolean.TRUE.equals(dataSourceViewModel.getNativeTracksState().getValue());
                File file = new File(((FileDataSource) dataSource).path);
                if (dataSource.isLoaded()) {
                    if (nativeTracks) {
                        Track track = ((FileDataSource) dataSource).tracks.get(0);
                        String distance = StringFormatter.distanceH(track.getDistance());
                        holder.description.setText(distance);
                        holder.icon.setImageResource(R.drawable.ic_track);
                        color = track.style.color;
                    } else {
                        int waypointsCount = ((FileDataSource) dataSource).waypoints.size();
                        int tracksCount = ((FileDataSource) dataSource).tracks.size();
                        int routesCount = ((FileDataSource) dataSource).routes.size();
                        StringBuilder sb = new StringBuilder();
                        if (waypointsCount > 0) {
                            sb.append(resources.getQuantityString(R.plurals.placesCount, waypointsCount, waypointsCount));
                            if (tracksCount > 0 || routesCount > 0)
                                sb.append(", ");
                        }
                        if (tracksCount > 0) {
                            sb.append(resources.getQuantityString(R.plurals.tracksCount, tracksCount, tracksCount));
                            if (routesCount > 0)
                                sb.append(", ");
                        }
                        if (routesCount > 0) {
                            sb.append(resources.getQuantityString(R.plurals.routesCount, routesCount, routesCount));
                        }
                        holder.description.setText(sb);
                        if (waypointsCount > 0 && tracksCount > 0)
                            holder.icon.setImageResource(R.drawable.ic_dataset);
                        else if (waypointsCount > 0)
                            holder.icon.setImageResource(R.drawable.ic_points);
                        else if (tracksCount > 0)
                            holder.icon.setImageResource(R.drawable.ic_tracks);
                    }
                } else {
                    String size = Formatter.formatShortFileSize(getContext(), file.length());
                    holder.description.setText(String.format(Locale.ENGLISH, "%s – %s", size, file.getName()));
                    if (nativeTracks)
                        holder.icon.setImageResource(R.drawable.ic_track);
                    else
                        holder.icon.setImageResource(R.drawable.ic_dataset);
                    color = mDisabledColor;
                }
                final boolean shown = dataSource.isVisible();
                if (shown)
                    holder.action.setImageResource(R.drawable.ic_visibility);
                else
                    holder.action.setImageResource(R.drawable.ic_visibility_off);
                holder.action.setVisibility(View.VISIBLE);
                holder.action.setOnClickListener(v -> {
                    mDataHolder.setDataSourceAvailability((FileDataSource) getItem(position), !shown);
                    notifyItemChanged(position);
                });
            }
            holder.icon.setImageTintList(ColorStateList.valueOf(color));
            holder.itemView.setOnClickListener(v -> {
                mFragmentHolder.disableListActionButton();
                mDataHolder.onDataSourceSelected(dataSource);
            });
            holder.itemView.setOnLongClickListener(v -> {
                PopupMenu popup = new PopupMenu(getContext(), v);
                popup.inflate(R.menu.context_menu_data_list);
                if (dataSource instanceof WaypointDbDataSource)
                    popup.getMenu().findItem(R.id.action_delete).setVisible(false);
                popup.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.action_share) {
                        mDataHolder.onDataSourceShare(dataSource);
                        return true;
                    }
                    if (itemId == R.id.action_delete) {
                        mDataHolder.onDataSourceDelete(dataSource);
                        return true;
                    }
                    return false;
                });
                popup.show();
                return true;
            });
        }

        class DataSourceViewHolder extends RecyclerView.ViewHolder {
            MaterialTextView name;
            MaterialTextView description;
            AppCompatImageView icon;
            AppCompatImageView action;

            DataSourceViewHolder(View view) {
                super(view);
                name = view.findViewById(R.id.name);
                description = view.findViewById(R.id.description);
                icon = view.findViewById(R.id.icon);
                action = view.findViewById(R.id.action);
            }
        }
    }

    public static final DiffUtil.ItemCallback<DataSource> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<DataSource>() {
                @Override
                public boolean areItemsTheSame(@NonNull DataSource oldSource, @NonNull DataSource newSource) {
                    return oldSource.equals(newSource);
                }

                @Override
                public boolean areContentsTheSame(@NonNull DataSource oldSource, @NonNull DataSource newSource) {
                    return oldSource.equals(newSource);
                }
            };

    private class XDataSourceListAdapter extends BaseAdapter {
        private final int mAccentColor;
        private final int mDisabledColor;

        XDataSourceListAdapter(Context context) {
            mAccentColor = context.getColor(R.color.colorAccent);
            mDisabledColor = context.getColor(R.color.colorPrimary);
        }

        @Override
        public DataSource getItem(int position) {
            List<DataSource> dataSources = dataSourceViewModel.getDataSourcesState().getValue();
            return dataSources.get(position);
        }

        @Override
        public long getItemId(int position) {
            List<DataSource> dataSources = dataSourceViewModel.getDataSourcesState().getValue();
            return dataSources.get(position).hashCode();
        }

        @Override
        public int getCount() {
            List<DataSource> dataSources = dataSourceViewModel.getDataSourcesState().getValue();
            return dataSources.size();
        }

        @Override
        public boolean isEnabled(int position) {
            DataSource dataSource = getItem(position);
            return dataSource.isLoaded();
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            DataSourceListItemHolder itemHolder;
            final DataSource dataSource = getItem(position);

            if (convertView == null) {
                itemHolder = new DataSourceListItemHolder();
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_data_source, parent, false);
                itemHolder.name = convertView.findViewById(R.id.name);
                itemHolder.description = convertView.findViewById(R.id.description);
                itemHolder.icon = convertView.findViewById(R.id.icon);
                itemHolder.action = convertView.findViewById(R.id.action);
                convertView.setTag(itemHolder);
            } else {
                itemHolder = (DataSourceListItemHolder) convertView.getTag();
            }

            itemHolder.name.setText(dataSource.name);
            Resources resources = getResources();

            int color = mAccentColor;
            if (dataSource instanceof WaypointDbDataSource) {
                int count = ((WaypointDataSource) dataSource).getWaypointsCount();
                itemHolder.description.setText(resources.getQuantityString(R.plurals.placesCount, count, count));
                itemHolder.icon.setImageResource(R.drawable.ic_points);
                itemHolder.action.setVisibility(View.GONE);
                itemHolder.action.setOnClickListener(null);
            } else {
                boolean nativeTracks = Boolean.TRUE.equals(dataSourceViewModel.getNativeTracksState().getValue());
                File file = new File(((FileDataSource) dataSource).path);
                if (dataSource.isLoaded()) {
                    if (nativeTracks) {
                        Track track = ((FileDataSource) dataSource).tracks.get(0);
                        String distance = StringFormatter.distanceH(track.getDistance());
                        itemHolder.description.setText(distance);
                        itemHolder.icon.setImageResource(R.drawable.ic_track);
                        color = track.style.color;
                    } else {
                        int waypointsCount = ((FileDataSource) dataSource).waypoints.size();
                        int tracksCount = ((FileDataSource) dataSource).tracks.size();
                        int routesCount = ((FileDataSource) dataSource).routes.size();
                        StringBuilder sb = new StringBuilder();
                        if (waypointsCount > 0) {
                            sb.append(resources.getQuantityString(R.plurals.placesCount, waypointsCount, waypointsCount));
                            if (tracksCount > 0 || routesCount > 0)
                                sb.append(", ");
                        }
                        if (tracksCount > 0) {
                            sb.append(resources.getQuantityString(R.plurals.tracksCount, tracksCount, tracksCount));
                            if (routesCount > 0)
                                sb.append(", ");
                        }
                        if (routesCount > 0) {
                            sb.append(resources.getQuantityString(R.plurals.routesCount, routesCount, routesCount));
                        }
                        itemHolder.description.setText(sb);
                        if (waypointsCount > 0 && tracksCount > 0)
                            itemHolder.icon.setImageResource(R.drawable.ic_dataset);
                        else if (waypointsCount > 0)
                            itemHolder.icon.setImageResource(R.drawable.ic_points);
                        else if (tracksCount > 0)
                            itemHolder.icon.setImageResource(R.drawable.ic_tracks);
                    }
                } else {
                    String size = Formatter.formatShortFileSize(getContext(), file.length());
                    itemHolder.description.setText(String.format(Locale.ENGLISH, "%s – %s", size, file.getName()));
                    if (nativeTracks)
                        itemHolder.icon.setImageResource(R.drawable.ic_track);
                    else
                        itemHolder.icon.setImageResource(R.drawable.ic_dataset);
                    color = mDisabledColor;
                }
                final boolean shown = dataSource.isVisible();
                if (shown)
                    itemHolder.action.setImageResource(R.drawable.ic_visibility);
                else
                    itemHolder.action.setImageResource(R.drawable.ic_visibility_off);
                itemHolder.action.setVisibility(View.VISIBLE);
                itemHolder.action.setOnClickListener(v -> {
                    mDataHolder.setDataSourceAvailability((FileDataSource) getItem(position), !shown);
                    notifyDataSetChanged();
                });
            }
            itemHolder.icon.setImageTintList(ColorStateList.valueOf(color));

            return convertView;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }

    private static class DataSourceListItemHolder {
        MaterialTextView name;
        MaterialTextView description;
        AppCompatImageView icon;
        AppCompatImageView action;
    }
}
