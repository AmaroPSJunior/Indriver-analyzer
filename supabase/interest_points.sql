create table if not exists public.interest_points (
  id text primary key,
  user_id text not null,
  name text not null,
  address text not null,
  icon text not null default '📍',
  visible boolean not null default true,
  latitude double precision,
  longitude double precision,
  updated_at timestamptz not null default now()
);

alter table public.interest_points enable row level security;
