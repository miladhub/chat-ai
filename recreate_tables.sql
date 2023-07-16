drop table if exists messages;

drop table if exists contexts;

drop table if exists model_functions;

create table messages (
  id bigserial PRIMARY KEY,
  embedding vector(1536),
  role varchar(10),
  contents varchar(4000) UNIQUE,
  message_ts timestamp not null 
);

create table contexts (
  id bigserial PRIMARY KEY,
  name varchar(100) UNIQUE,
  value varchar(1000) not null
);

create table model_functions (
  id bigserial PRIMARY KEY,
  name varchar(100) UNIQUE,
  body json not null
);
